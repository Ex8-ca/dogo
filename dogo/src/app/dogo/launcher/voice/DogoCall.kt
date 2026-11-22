/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.voice

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import app.dogo.launcher.agent.backend.AgentCatalog
import app.dogo.launcher.agent.backend.ChatTurn
import app.dogo.launcher.agent.backend.resolve
import app.dogo.launcher.privilege.ActionRequirements
import app.dogo.launcher.privilege.SystemAccess
import app.dogo.launcher.profile.AssistantProfile
import app.dogo.launcher.profile.ProfileStore
import app.dogo.launcher.profile.ProfileText
import app.dogo.launcher.ui.options.OptionsStore
import app.dogo.launcher.ui.setup.Assistant
import app.dogo.launcher.ui.setup.ConversationCodec
import app.dogo.launcher.ui.setup.PhoneFacts
import app.dogo.launcher.ui.setup.SetupChatAgent
import app.dogo.launcher.ui.setup.SetupChatViewModel.ActionState
import app.dogo.launcher.ui.setup.SetupChatViewModel.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Dogo call: one live voice conversation, started from the light in the
 * centre of the Dogo Ring (like Hermes Vessel's orb) or from Dogo Setup.
 *
 * Holds the call's state for the orb, live captions, and the cards for any
 * changes the voice proposes. Real work (web, files, memory) is handed to
 * Hermes when a Hermes server is set up.
 */
object DogoCall {

    enum class Phase { IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, MUTED, ERROR }

    data class State(
        val phase: Phase = Phase.IDLE,
        val caption: String = "",
        val heard: String = "",
        /** What Hermes is working on, while [Phase.THINKING]. */
        val working: String = "",
        val workingSince: Long = 0L,
        /** Changes proposed during this call, by proposal id in [Assistant]. */
        val cards: List<Long> = emptyList(),
        val error: String = "",
        val startedAt: Long = 0L,
    ) {
        val active: Boolean get() = phase != Phase.IDLE && phase != Phase.ERROR
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 0..1, updated about 20 times a second during a call. */
    val level = MutableStateFlow(0f)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: VoiceSession? = null
    private var handoff: HermesHandoff? = null
    private var meter: Job? = null
    private var muted = false

    /** What the voice should say first on this call; null once said, or when there's nothing to say. */
    private var pendingGreeting: String? = null
    private var injectedTest = false

    /** The last caption was a finished line; the next words start a new one. */
    private var captionDone = true
    private lateinit var app: Context

    /**
     * Starts talking in the open conversation. [fresh] (the light on the home
     * screen) starts a new one first if the open one has gone quiet.
     */
    fun start(context: Context, fresh: Boolean = false) {
        if (_state.value.active) return
        app = context.applicationContext
        Assistant.init(app)
        if (Assistant.state.value.messages.any { it.notificationContext }) {
            _state.value = State(phase = Phase.ERROR, error = "Voice cannot safely resume notification context yet. Start a new conversation for voice.")
            return
        }
        if (fresh) Assistant.freshIfIdle()
        val config = VoiceStore(app).load()
        config.problem()?.let {
            _state.value = State(phase = Phase.ERROR, error = "Voice isn't set up yet. $it")
            return
        }
        muted = false
        _state.value = State(phase = Phase.CONNECTING, startedAt = System.currentTimeMillis())
        scope.launch {
            val tokenJob = async { VoiceTokens.fetch(config) }
            val phone = withContext(Dispatchers.IO) { phoneContext(app) }
            val token = tokenJob.await().getOrElse { e ->
                fail("Couldn't start: ${e.message}")
                return@launch
            }
            if (_state.value.phase != Phase.CONNECTING) return@launch // cancelled while connecting
            handoff = hermes(app)
            val profile = ProfileStore(app).load()
            val open = Assistant.state.value
            pendingGreeting = ProfileText.greeting(
                profile,
                // Only worth resuming if the user said or typed something; a lone greeting isn't a topic.
                if (open.messages.any { it.fromUser && !it.note }) ConversationCodec.displayTitle(open.title, open.messages) else null,
            )
            val instructions = SetupChatAgent.systemPromptFor(phone, profile) + historyNote() + earlierNote(profile)
            val injected = if (com.android.launcher3.BuildConfig.DEBUG) java.io.File(app.filesDir, "voice_test.pcm") else null
            injectedTest = injected?.exists() == true
            session = VoiceSession(
                token,
                config.model,
                instructions,
                config.voice,
                handoff = handoff != null,
                onEvent = { e -> scope.launch { onEvent(e) } },
                injectedSpeech = injected,
            ).also { it.start() }
        }
    }

    /** A few lines about recent conversations, so a call feels like a continuation. Never notification chats. */
    private fun earlierNote(profile: AssistantProfile): String {
        if (!profile.recallEarlier) return ""
        val digest = ProfileText.earlierConversations(
            Assistant.history.value,
            Assistant.state.value.conversationId,
            System.currentTimeMillis(),
        )
        return if (digest.isEmpty()) "" else "\n\n$digest"
    }

    /** The conversation so far, typed or spoken, so a call picks up where it left off. */
    private fun historyNote(): String {
        val turns = Assistant.turns()
        if (turns.isEmpty()) return ""
        val lines = turns.takeLast(24).map { (if (it.role == ChatTurn.Role.USER) "User: " else "Assistant: ") + it.text.take(600) }
        var text = lines.joinToString("\n")
        if (text.length > 8_000) text = text.takeLast(8_000)
        return "\n\nThis continues an earlier conversation (typed or spoken). Carry on from it; don't repeat changes already applied.\n$text"
    }

    fun stop() {
        val wasActive = _state.value.phase != Phase.IDLE
        session?.stop()
        session = null
        handoff = null
        meter?.cancel()
        level.value = 0f
        _state.value = State()
        if (wasActive) Assistant.flush()
    }

    fun toggle(context: Context) = if (_state.value.active) stop() else start(context)

    fun setMuted(value: Boolean) {
        muted = value
        session?.setMuted(value)
        _state.update { it.copy(phase = if (value) Phase.MUTED else Phase.LISTENING) }
    }

    /** Tapping the light while the voice talks cuts it off, like Vessel. */
    fun interrupt() {
        session?.send(RealtimeProtocol.cancelResponse())
    }

    /** Tells the voice something happened, e.g. a change was applied from the chat. */
    fun tell(text: String) {
        session?.send(RealtimeProtocol.note(text))
    }

    fun dismissCard(id: Long) {
        Assistant.dismiss(id)
        _state.update { s -> s.copy(cards = s.cards - id) }
    }

    fun clearError() = _state.update { if (it.phase == Phase.ERROR) State() else it }

    fun approve(id: Long) {
        Assistant.approve(id) { outcome ->
            if (outcome.ok) {
                scope.launch {
                    delay(2_500)
                    _state.update { s -> s.copy(cards = s.cards - id) }
                }
            }
        }
    }

    private fun fail(message: String) {
        session?.stop()
        session = null
        meter?.cancel()
        level.value = 0f
        _state.update { it.copy(phase = Phase.ERROR, error = message) }
        Assistant.flush()
    }

    private suspend fun onEvent(event: RealtimeProtocol.Event) {
        when (event) {
            RealtimeProtocol.Event.Ready -> {
                _state.update { it.copy(phase = if (muted) Phase.MUTED else Phase.LISTENING) }
                startMeter()
                // Speak first, once. Not while muted, and not during the debug audio test.
                pendingGreeting?.let { line -> if (!muted && !injectedTest) session?.send(RealtimeProtocol.greet(line)) }
                pendingGreeting = null
            }

            RealtimeProtocol.Event.UserStartedSpeaking -> _state.update {
                if (it.phase == Phase.THINKING) it else it.copy(phase = Phase.LISTENING, caption = "")
            }

            is RealtimeProtocol.Event.UserSaid -> {
                Assistant.add(Message(event.text, fromUser = true, spoken = true))
                _state.update { it.copy(heard = event.text) }
            }

            is RealtimeProtocol.Event.AssistantDelta -> {
                val fresh = captionDone
                captionDone = false
                _state.update { it.copy(caption = ((if (fresh) "" else it.caption) + event.text).takeLast(240)) }
            }

            is RealtimeProtocol.Event.AssistantSaid -> {
                Assistant.add(Message(event.text, fromUser = false, spoken = true))
                captionDone = true
                _state.update { it.copy(caption = event.text) }
            }

            is RealtimeProtocol.Event.ProposeChanges -> {
                val proposals = Assistant.proposalsFor(event.actions)
                if (proposals.isNotEmpty()) {
                    Assistant.add(Message(if (proposals.size == 1) "Proposed change:" else "Proposed changes:", fromUser = false, proposals = proposals, spoken = true))
                    _state.update { it.copy(cards = (it.cards + proposals.map { p -> p.id }).takeLast(4)) }
                }
                RealtimeProtocol.toolResult(event.callId, RealtimeProtocol.proposalResult(proposals.size, event.rejected)).forEach { session?.send(it) }
                if (!OptionsStore(app).load().requireConfirmation) proposals.filter { it.state is ActionState.Ready }.forEach { approve(it.id) }
            }

            is RealtimeProtocol.Event.UseTools -> {
                val bridge = handoff
                Assistant.add(Message("Asked Hermes to look into it", fromUser = false, note = true))
                _state.update { it.copy(phase = Phase.THINKING, working = event.request, workingSince = System.currentTimeMillis()) }
                val answer = bridge?.ask(event.request) ?: "Hermes isn't set up on this phone, so I can't use those tools."
                bridge?.sessionId?.let { Assistant.rememberHermesSession(it) }
                _state.update { it.copy(phase = if (muted) Phase.MUTED else Phase.LISTENING, working = "") }
                RealtimeProtocol.toolResult(event.callId, answer.take(6_000)).forEach { session?.send(it) }
            }

            is RealtimeProtocol.Event.Failed -> fail(event.message)

            else -> Unit
        }
    }

    /** Drives the orb from real loudness, and SPEAKING from actual playback. */
    private fun startMeter() {
        meter?.cancel()
        meter = scope.launch {
            while (isActive) {
                val s = session ?: break
                val playing = s.isPlaying
                level.value = if (playing) {
                    s.outLevel
                } else if (muted) {
                    0f
                } else {
                    s.micLevel
                }
                _state.update { st ->
                    when {
                        st.phase == Phase.THINKING || st.phase == Phase.ERROR || st.phase == Phase.IDLE -> st
                        playing && st.phase != Phase.SPEAKING -> st.copy(phase = Phase.SPEAKING)
                        !playing && st.phase == Phase.SPEAKING -> st.copy(phase = if (muted) Phase.MUTED else Phase.LISTENING)
                        else -> st
                    }
                }
                delay(50)
            }
        }
    }

    private fun hermes(context: Context): HermesHandoff? {
        val agent = OptionsStore(context).load().agentConfigs[AgentCatalog.HERMES.id] ?: return null
        val resolved = AgentCatalog.HERMES.resolve(agent)
        if (resolved.problems().isNotEmpty() || resolved.apiKey.isNullOrBlank()) return null
        return HermesHandoff(resolved.baseUrl, resolved.apiKey!!, initialSession = Assistant.hermesSession())
    }

    private suspend fun phoneContext(context: Context): SetupChatAgent.PhoneContext {
        val apps = runCatching {
            context.getSystemService(LauncherApps::class.java)
                .getActivityList(null, Process.myUserHandle())
                .filter { it.componentName.packageName != context.packageName }
                .map { SetupChatAgent.InstalledApp(it.label.toString(), it.componentName.packageName) }
                .distinctBy { it.packageName }
        }.getOrDefault(emptyList())
        val access = SystemAccess(context)
        val availability = access.availability()
        val pluginActions = access.enabledPlugins().flatMap { plugin ->
            plugin.manifest.actions.map { spec -> SetupChatAgent.PluginAction(plugin.manifest.qualifiedAction(spec.name), spec.description, spec.params) }
        }
        return SetupChatAgent.PhoneContext(
            apps = apps,
            pluginActions = pluginActions,
            systemDarkModeAvailable = availability.shellTiers.isNotEmpty(),
            animationScaleAvailable = ActionRequirements.WRITE_SECURE_SETTINGS in availability.grantedPermissions || availability.shellTiers.isNotEmpty(),
            widgets = PhoneFacts.widgets(context),
            home = PhoneFacts.home(context),
            iconPacks = PhoneFacts.iconPacks(context),
        )
    }
}
