/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ui.setup

import android.app.Application
import android.content.pm.LauncherApps
import android.os.Process
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.dogo.launcher.agent.Action
import app.dogo.launcher.agent.ActionExecutor
import app.dogo.launcher.agent.AgentOptions
import app.dogo.launcher.agent.backend.AgentBackend
import app.dogo.launcher.agent.backend.AgentBackends
import app.dogo.launcher.agent.backend.ChatTurn
import app.dogo.launcher.agent.backend.OnDeviceBackend
import app.dogo.launcher.agent.backend.ReplyOptions
import app.dogo.launcher.agent.backend.resolve
import app.dogo.launcher.llm.HardwareProfile
import app.dogo.launcher.llm.ModelCatalog
import app.dogo.launcher.llm.ModelRunner
import app.dogo.launcher.privilege.ActionApplier
import app.dogo.launcher.privilege.ActionRequirements
import app.dogo.launcher.privilege.PrivilegePlanner
import app.dogo.launcher.privilege.SystemAccess
import app.dogo.launcher.ui.options.OptionsStore
import app.dogo.launcher.ui.setup.SetupChatAgent.InstalledApp
import app.dogo.launcher.ui.setup.SetupChatAgent.PhoneContext
import app.dogo.launcher.voice.RealtimeProtocol
import app.dogo.launcher.voice.VoiceSession
import app.dogo.launcher.voice.VoiceStore
import app.dogo.launcher.voice.VoiceTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the setup chat: sends the conversation to the chosen agent, parses
 * any actions it proposes, and holds the transcript.
 *
 * Nothing is applied until the user approves it. Proposed actions are
 * checked against the same [AgentOptions] gates whichever agent proposed them,
 * then carried out by [ActionApplier] through the least powerful route that
 * works.
 */
class SetupChatViewModel(
    application: Application,
) : AndroidViewModel(application) {

    /** Where a proposed action stands. */
    sealed interface ActionState {
        /** Can run now. [route] names how, e.g. "Launcher only" or "Shizuku". */
        data class Ready(val route: String) : ActionState

        /** Switched off in Dogo Options. */
        data object Disallowed : ActionState

        /** Needs more access; [hint] says how to get it. */
        data class Blocked(val hint: String) : ActionState
        data object Applying : ActionState
        data class Applied(val message: String) : ActionState
        data class Failed(val message: String) : ActionState
        data object Dismissed : ActionState
    }

    data class Proposal(val id: Long, val action: Action, val state: ActionState)

    data class Message(
        val text: String,
        val fromUser: Boolean,
        val proposals: List<Proposal> = emptyList(),
    )

    data class UiState(
        val messages: List<Message> = emptyList(),
        val busy: Boolean = false,
        val modelReady: Boolean = false,
        val modelName: String? = null,
        val status: String? = null,
        val options: AgentOptions = AgentOptions(),
        val voice: VoiceState = VoiceState.Off,
    )

    /** The live voice conversation, if any. */
    sealed interface VoiceState {
        data object Off : VoiceState
        data object Connecting : VoiceState

        /** [speaking] is true while the user is talking. */
        data class Live(val speaking: Boolean = false, val muted: Boolean = false) : VoiceState
    }

    private val turns = mutableListOf<ChatTurn>()
    private val runner = ModelRunner.forContext(application)
    private val optionsStore = OptionsStore(application)
    private var backend: AgentBackend? = null
    private val access = SystemAccess(application)
    private val applier = ActionApplier(application, access)
    private var nextProposalId = 0L

    /** Refreshed before each turn, so the agent sees what is actually possible. */
    @Volatile private var phone = PhoneContext()
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        reloadOptions()
    }

    /**
     * Re-reads the options and switches agent if they changed. The chat screen
     * calls this when it resumes, so a change made in Dogo Options applies on
     * return without restarting the launcher.
     */
    fun reloadOptions() {
        if (_uiState.value.busy) return
        val options = optionsStore.load()
        _uiState.update { it.copy(options = options) }

        if (options.isOnDevice) {
            backend = OnDeviceBackend(runner) { phone }
            // If a model was already picked and downloaded, load it so the
            // chat is usable the moment it opens.
            val variant = options.resolveModel(HardwareProfile.fromDevice(getApplication()))
            if (variant != null && runner.loadedVariant?.id != variant.id && runner.isAvailable(variant)) {
                loadModel(variant)
            } else {
                refreshOnDeviceReadiness()
            }
        } else {
            // A resident local model holds gigabytes the launcher can't spare.
            runner.close()
            val agent = options.resolvedAgent()
            val problem = agent.problems().firstOrNull()
            backend = if (problem == null) AgentBackends.create(getApplication(), agent) else null
            _uiState.update {
                it.copy(
                    modelReady = backend != null,
                    modelName = backend?.label,
                    status = problem?.let { p ->
                        "${agent.preset.displayName} isn't set up yet. ${p.message}"
                    },
                )
            }
        }
    }

    private fun refreshOnDeviceReadiness() {
        _uiState.update {
            it.copy(
                modelReady = runner.isReady(),
                modelName = runner.loadedVariant?.displayName,
                status = if (runner.isReady()) {
                    null
                } else {
                    "No model loaded. Open Dogo Options to download one, or choose another agent."
                },
            )
        }
    }

    /** Loads [variant] so replies can be generated on-device. */
    fun loadModel(variant: ModelCatalog.ModelVariant) {
        _uiState.update { it.copy(busy = true, status = "Loading ${variant.displayName}…") }
        viewModelScope.launch {
            runner.load(variant)
                .onSuccess { _uiState.update { it.copy(busy = false, status = null) } }
                .onFailure { e ->
                    _uiState.update { it.copy(busy = false, status = "Could not load the model: ${e.message}") }
                }
            refreshOnDeviceReadiness()
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _uiState.value.busy) return
        val active = backend
        if (active == null || !_uiState.value.modelReady) {
            _uiState.update {
                it.copy(status = it.status ?: "No agent is ready. Open Dogo Options to choose one.")
            }
            return
        }

        _uiState.update { state ->
            state.copy(
                messages = state.messages + Message(trimmed, fromUser = true),
                busy = true,
                status = null,
            )
        }
        turns += ChatTurn(ChatTurn.Role.USER, trimmed)

        viewModelScope.launch {
            phone = loadPhoneContext()
            val options = _uiState.value.options
            active.reply(
                SetupChatAgent.systemPromptFor(phone),
                turns.toList(),
                ReplyOptions(effort = options.thinkingLevel.effort),
            )
                .onSuccess { reply ->
                    turns += ChatTurn(ChatTurn.Role.ASSISTANT, reply)
                    val proposals = ActionExecutor.parseActions(reply).map { action ->
                        Proposal(nextProposalId++, action, initialState(action, options))
                    }
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages + Message(reply, fromUser = false, proposals = proposals),
                            busy = false,
                        )
                    }
                    if (!options.requireConfirmation) autoApply(proposals)
                }
                .onFailure { e ->
                    // Keep what the user typed on screen, but take the turn out
                    // of the history so a retry doesn't send it twice.
                    turns.removeAt(turns.lastIndex)
                    _uiState.update {
                        it.copy(busy = false, status = "The agent couldn't reply: ${e.message}")
                    }
                }
        }
    }

    /** Carries out an approved proposal. */
    fun approve(proposalId: Long) {
        val proposal = findProposal(proposalId) ?: return
        if (proposal.state !is ActionState.Ready) return
        if (!_uiState.value.options.isAllowed(proposal.action)) {
            setProposalState(proposalId, ActionState.Disallowed)
            return
        }
        setProposalState(proposalId, ActionState.Applying)
        viewModelScope.launch {
            val outcome = applier.apply(proposal.action)
            setProposalState(
                proposalId,
                if (outcome.ok) ActionState.Applied(outcome.message) else ActionState.Failed(outcome.message),
            )
            tellVoice(if (outcome.ok) "The user applied a change: ${outcome.message}" else "A change failed: ${outcome.message}")
        }
    }

    fun dismiss(proposalId: Long) {
        val proposal = findProposal(proposalId) ?: return
        if (proposal.state is ActionState.Applying || proposal.state is ActionState.Applied) return
        setProposalState(proposalId, ActionState.Dismissed)
    }

    /**
     * Re-checks proposals that were waiting for access, e.g. after the user
     * set up Shizuku and came back to the chat.
     */
    fun recheckBlocked() {
        val options = _uiState.value.options
        viewModelScope.launch {
            val availability = access.availability()
            _uiState.update { state ->
                state.copy(
                    messages = state.messages.map { message ->
                        message.copy(
                            proposals = message.proposals.map { p ->
                                if (p.state is ActionState.Blocked || p.state is ActionState.Ready || p.state is ActionState.Disallowed) {
                                    p.copy(state = stateFor(p.action, options, availability))
                                } else {
                                    p
                                }
                            },
                        )
                    },
                )
            }
        }
    }

    /**
     * Applies every proposal the user has allowed to skip confirmation. Runs
     * after a reply arrives when "Confirm every action" is off.
     */
    private fun autoApply(proposals: List<Proposal>) {
        proposals.filter { it.state is ActionState.Ready }.forEach { approve(it.id) }
    }

    private suspend fun initialState(action: Action, options: AgentOptions): ActionState = stateFor(action, options, access.availability())

    private fun stateFor(
        action: Action,
        options: AgentOptions,
        availability: PrivilegePlanner.Availability,
    ): ActionState {
        if (!options.isAllowed(action)) return ActionState.Disallowed
        return when (val plan = PrivilegePlanner.plan(action, availability, getApplication<Application>().packageName)) {
            is PrivilegePlanner.Plan.Blocked -> ActionState.Blocked(plan.hint)
            PrivilegePlanner.Plan.InLauncher -> ActionState.Ready("Launcher only")
            is PrivilegePlanner.Plan.WithPermission -> ActionState.Ready("Granted permission")
            is PrivilegePlanner.Plan.ViaShell -> ActionState.Ready(plan.tier.title)
            is PrivilegePlanner.Plan.ViaPlugin -> ActionState.Ready("Plugin ${plan.pluginId}")
        }
    }

    private fun findProposal(id: Long): Proposal? = _uiState.value.messages.asSequence().flatMap { it.proposals }.firstOrNull { it.id == id }

    private fun setProposalState(id: Long, state: ActionState) {
        _uiState.update { ui ->
            ui.copy(
                messages = ui.messages.map { m ->
                    if (m.proposals.none { it.id == id }) {
                        m
                    } else {
                        m.copy(proposals = m.proposals.map { if (it.id == id) it.copy(state = state) else it })
                    }
                },
            )
        }
    }

    private suspend fun loadPhoneContext(): PhoneContext = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val apps = runCatching {
            app.getSystemService(LauncherApps::class.java)
                .getActivityList(null, Process.myUserHandle())
                .filter { it.componentName.packageName != app.packageName }
                .map { InstalledApp(it.label.toString(), it.componentName.packageName) }
                .distinctBy { it.packageName }
        }.getOrDefault(emptyList())
        val availability = access.availability()
        val pluginActions = access.enabledPlugins().flatMap { plugin ->
            plugin.manifest.actions.map { spec ->
                SetupChatAgent.PluginAction(plugin.manifest.qualifiedAction(spec.name), spec.description, spec.params)
            }
        }
        PhoneContext(
            pluginActions = pluginActions,
            apps = apps,
            systemDarkModeAvailable = availability.shellTiers.isNotEmpty(),
            animationScaleAvailable = ActionRequirements.WRITE_SECURE_SETTINGS in availability.grantedPermissions ||
                availability.shellTiers.isNotEmpty(),
        )
    }

    fun dismissStatus() {
        _uiState.update { it.copy(status = null) }
    }

    // ---- voice ---------------------------------------------------------------

    private var voice: VoiceSession? = null
    private var handoff: app.dogo.launcher.voice.HermesHandoff? = null

    private fun hermesHandoff(): app.dogo.launcher.voice.HermesHandoff? {
        val options = _uiState.value.options
        val hermes = app.dogo.launcher.agent.backend.AgentCatalog.HERMES
        val resolved = hermes.resolve(options.agentConfigs[hermes.id])
        val key = resolved.apiKey
        if (resolved.problems().isNotEmpty() || key.isNullOrBlank()) return null
        return app.dogo.launcher.voice.HermesHandoff(resolved.baseUrl, key)
    }
    private val voiceStore = VoiceStore(application)

    /** Starts or stops talking. The screen asks for the microphone first. */
    fun toggleVoice() {
        if (_uiState.value.voice != VoiceState.Off) {
            stopVoice()
            return
        }
        val config = voiceStore.load()
        config.problem()?.let { problem ->
            _uiState.update { it.copy(status = "Voice isn't set up yet. $problem (Dogo Options > Voice)") }
            return
        }
        _uiState.update { it.copy(voice = VoiceState.Connecting, status = null) }
        viewModelScope.launch {
            // Fetch the key while the app list loads; both take a moment.
            val tokenJob = viewModelScope.async { VoiceTokens.fetch(config) }
            phone = loadPhoneContext()
            val token = tokenJob.await().getOrElse { e ->
                _uiState.update { it.copy(voice = VoiceState.Off, status = "Couldn't start voice: ${e.message}") }
                return@launch
            }
            // Earlier typed turns give the voice agent the same context.
            val history = turns.takeLast(10).joinToString("\n") { (if (it.role == ChatTurn.Role.USER) "User: " else "Assistant: ") + it.text }
            val instructions = SetupChatAgent.systemPromptFor(phone) +
                if (history.isNotEmpty()) "\n\nConversation so far (typed):\n$history" else ""
            // Debug builds: files/voice_test.pcm (24 kHz mono 16-bit) is "spoken" once, for on-device tests.
            val injected = if (com.android.launcher3.BuildConfig.DEBUG) java.io.File(getApplication<Application>().filesDir, "voice_test.pcm") else null
            handoff = hermesHandoff()
            voice = VoiceSession(token, config.model, instructions, config.voice, handoff = handoff != null, onEvent = { event ->
                viewModelScope.launch(Dispatchers.Main) { onVoiceEvent(event) }
            }, injectedSpeech = injected).also { it.start() }
        }
    }

    fun setVoiceMuted(muted: Boolean) {
        voice?.setMuted(muted)
        _uiState.update { s -> (s.voice as? VoiceState.Live)?.let { s.copy(voice = it.copy(muted = muted)) } ?: s }
    }

    fun stopVoice() {
        voice?.stop()
        voice = null
        _uiState.update { it.copy(voice = VoiceState.Off) }
    }

    private suspend fun onVoiceEvent(event: RealtimeProtocol.Event) {
        when (event) {
            RealtimeProtocol.Event.Ready -> _uiState.update { it.copy(voice = VoiceState.Live()) }

            RealtimeProtocol.Event.UserStartedSpeaking, RealtimeProtocol.Event.UserStoppedSpeaking -> _uiState.update { s ->
                (s.voice as? VoiceState.Live)?.let { s.copy(voice = it.copy(speaking = event == RealtimeProtocol.Event.UserStartedSpeaking)) } ?: s
            }

            is RealtimeProtocol.Event.UserSaid -> {
                turns += ChatTurn(ChatTurn.Role.USER, event.text)
                _uiState.update { it.copy(messages = it.messages + Message(event.text, fromUser = true)) }
            }

            is RealtimeProtocol.Event.AssistantSaid -> {
                turns += ChatTurn(ChatTurn.Role.ASSISTANT, event.text)
                _uiState.update { it.copy(messages = it.messages + Message(event.text, fromUser = false)) }
            }

            is RealtimeProtocol.Event.ProposeChanges -> {
                val options = _uiState.value.options
                val proposals = event.actions.map { Proposal(nextProposalId++, it, initialState(it, options)) }
                if (proposals.isNotEmpty()) {
                    _uiState.update { it.copy(messages = it.messages + Message(proposals.size.let { n -> if (n == 1) "Proposed change:" else "Proposed changes:" }, fromUser = false, proposals = proposals)) }
                    if (!options.requireConfirmation) autoApply(proposals)
                }
                RealtimeProtocol.toolResult(event.callId, RealtimeProtocol.proposalResult(proposals.size, event.rejected)).forEach { voice?.send(it) }
            }

            is RealtimeProtocol.Event.UseTools -> {
                _uiState.update { it.copy(messages = it.messages + Message("Looking into it on your computer…", fromUser = false)) }
                val answer = handoff?.ask(event.request) ?: "Hermes isn't set up on this phone, so I can't use those tools."
                RealtimeProtocol.toolResult(event.callId, answer.take(6_000)).forEach { voice?.send(it) }
            }

            is RealtimeProtocol.Event.Failed -> {
                voice?.stop()
                voice = null
                _uiState.update { it.copy(voice = VoiceState.Off, status = "Voice stopped: ${event.message}") }
            }

            else -> Unit
        }
    }

    /** Lets the voice agent know a card was applied, so it can mention it. */
    private fun tellVoice(text: String) {
        voice?.send(RealtimeProtocol.note(text))
    }

    override fun onCleared() {
        voice?.stop()
        super.onCleared()
        runner.close()
    }
}
