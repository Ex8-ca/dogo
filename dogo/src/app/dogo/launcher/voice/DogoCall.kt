/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.voice

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import app.dogo.launcher.agent.Action
import app.dogo.launcher.agent.backend.AgentCatalog
import app.dogo.launcher.agent.backend.resolve
import app.dogo.launcher.privilege.ActionApplier
import app.dogo.launcher.privilege.ActionRequirements
import app.dogo.launcher.privilege.PrivilegePlanner
import app.dogo.launcher.privilege.SystemAccess
import app.dogo.launcher.ui.options.OptionsStore
import app.dogo.launcher.ui.setup.SetupChatAgent
import app.dogo.launcher.ui.setup.describeAction
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

    data class Card(val id: Long, val action: Action, val title: String, val state: CardState)

    sealed interface CardState {
        data class Ready(val route: String) : CardState
        data class Blocked(val hint: String) : CardState
        data object Applying : CardState
        data class Done(val message: String) : CardState
        data class Failed(val message: String) : CardState
    }

    data class State(
        val phase: Phase = Phase.IDLE,
        val caption: String = "",
        val heard: String = "",
        /** What Hermes is working on, while [Phase.THINKING]. */
        val working: String = "",
        val workingSince: Long = 0L,
        val cards: List<Card> = emptyList(),
        val error: String = "",
        val startedAt: Long = 0L,
    ) {
        val active: Boolean get() = phase != Phase.IDLE && phase != Phase.ERROR
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** 0..1, updated about 20 times a second during a call. */
    val level = MutableStateFlow(0f)

    /** Finished lines, for whoever wants a transcript (the chat). */
    val lines = MutableStateFlow<List<Pair<Boolean, String>>>(emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: VoiceSession? = null
    private var handoff: HermesHandoff? = null
    private var meter: Job? = null
    private var nextCard = 0L
    private var muted = false

    /** The last caption was a finished line; the next words start a new one. */
    private var captionDone = true
    private lateinit var app: Context

    /** Instructions extra to the setup prompt, e.g. the chat's typed history. */
    fun start(context: Context, extraInstructions: String = "") {
        if (_state.value.active) return
        app = context.applicationContext
        val config = VoiceStore(app).load()
        config.problem()?.let {
            _state.value = State(phase = Phase.ERROR, error = "Voice isn't set up yet. $it")
            return
        }
        muted = false
        lines.value = emptyList()
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
            val instructions = SetupChatAgent.systemPromptFor(phone) + extraInstructions
            val injected = if (com.android.launcher3.BuildConfig.DEBUG) java.io.File(app.filesDir, "voice_test.pcm") else null
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

    fun stop() {
        session?.stop()
        session = null
        handoff = null
        meter?.cancel()
        level.value = 0f
        _state.update { State(cards = it.cards.filter { c -> c.state is CardState.Ready || c.state is CardState.Applying }) }
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

    fun dismissCard(id: Long) = _state.update { s -> s.copy(cards = s.cards.filterNot { it.id == id }) }

    fun clearError() = _state.update { if (it.phase == Phase.ERROR) State() else it }

    fun approve(id: Long) {
        val card = _state.value.cards.firstOrNull { it.id == id } ?: return
        if (card.state !is CardState.Ready) return
        setCard(id, CardState.Applying)
        scope.launch {
            val outcome = ActionApplier(app, SystemAccess(app)).apply(card.action)
            setCard(id, if (outcome.ok) CardState.Done(outcome.message) else CardState.Failed(outcome.message))
            session?.send(RealtimeProtocol.note(if (outcome.ok) "The user applied a change: ${outcome.message}" else "A change failed: ${outcome.message}"))
            if (outcome.ok) {
                delay(2_500)
                dismissCard(id)
            }
        }
    }

    private fun setCard(id: Long, state: CardState) = _state.update { s ->
        s.copy(cards = s.cards.map { if (it.id == id) it.copy(state = state) else it })
    }

    private fun fail(message: String) {
        session?.stop()
        session = null
        meter?.cancel()
        level.value = 0f
        _state.update { it.copy(phase = Phase.ERROR, error = message) }
    }

    private suspend fun onEvent(event: RealtimeProtocol.Event) {
        when (event) {
            RealtimeProtocol.Event.Ready -> {
                _state.update { it.copy(phase = if (muted) Phase.MUTED else Phase.LISTENING) }
                startMeter()
            }

            RealtimeProtocol.Event.UserStartedSpeaking -> _state.update {
                if (it.phase == Phase.THINKING) it else it.copy(phase = Phase.LISTENING, caption = "")
            }

            is RealtimeProtocol.Event.UserSaid -> {
                lines.update { (it + (true to event.text)).takeLast(40) }
                _state.update { it.copy(heard = event.text) }
            }

            is RealtimeProtocol.Event.AssistantDelta -> {
                val fresh = captionDone
                captionDone = false
                _state.update { it.copy(caption = ((if (fresh) "" else it.caption) + event.text).takeLast(240)) }
            }

            is RealtimeProtocol.Event.AssistantSaid -> {
                lines.update { (it + (false to event.text)).takeLast(40) }
                captionDone = true
                _state.update { it.copy(caption = event.text) }
            }

            is RealtimeProtocol.Event.ProposeChanges -> {
                val access = SystemAccess(app)
                val availability = access.availability()
                val options = OptionsStore(app).load()
                val cards = event.actions.map { action ->
                    val state: CardState = when {
                        !options.isAllowed(action) -> CardState.Blocked("Switched off in Dogo Options.")

                        else -> when (val plan = PrivilegePlanner.plan(action, availability, app.packageName)) {
                            is PrivilegePlanner.Plan.Blocked -> CardState.Blocked(plan.hint)
                            PrivilegePlanner.Plan.InLauncher -> CardState.Ready("Launcher only")
                            is PrivilegePlanner.Plan.WithPermission -> CardState.Ready("Granted permission")
                            is PrivilegePlanner.Plan.ViaShell -> CardState.Ready(plan.tier.title)
                            is PrivilegePlanner.Plan.ViaPlugin -> CardState.Ready("Plugin")
                        }
                    }
                    Card(nextCard++, action, describeAction(action), state)
                }
                _state.update { it.copy(cards = (it.cards + cards).takeLast(4)) }
                RealtimeProtocol.toolResult(event.callId, RealtimeProtocol.proposalResult(cards.size, event.rejected)).forEach { session?.send(it) }
                if (!options.requireConfirmation) cards.filter { it.state is CardState.Ready }.forEach { approve(it.id) }
            }

            is RealtimeProtocol.Event.UseTools -> {
                val bridge = handoff
                _state.update { it.copy(phase = Phase.THINKING, working = event.request, workingSince = System.currentTimeMillis()) }
                val answer = bridge?.ask(event.request) ?: "Hermes isn't set up on this phone, so I can't use those tools."
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
        return HermesHandoff(resolved.baseUrl, resolved.apiKey!!)
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
        )
    }
}
