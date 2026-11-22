/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ui.setup

import android.content.Context
import android.graphics.Bitmap
import app.dogo.launcher.agent.Action
import app.dogo.launcher.agent.AgentOptions
import app.dogo.launcher.agent.backend.ChatTurn
import app.dogo.launcher.privilege.ActionApplier
import app.dogo.launcher.privilege.PrivilegePlanner
import app.dogo.launcher.privilege.SystemAccess
import app.dogo.launcher.ui.options.OptionsStore
import app.dogo.launcher.ui.setup.SetupChatViewModel.ActionState
import app.dogo.launcher.ui.setup.SetupChatViewModel.Message
import app.dogo.launcher.ui.setup.SetupChatViewModel.Proposal
import app.dogo.launcher.voice.DogoCall
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one conversation with Dogo, whether it's typed in the chat or spoken to
 * the light on the home screen, and the saved ones before it.
 *
 * Lives for the launcher process, not one screen: the call on the home screen
 * and the chat in settings write into the same conversation, and applying an
 * accent (which restarts the settings screen) doesn't lose anything. Every
 * change is saved to [ConversationStore] a moment later.
 */
object Assistant {
    /** Opening Dogo after this long starts a new conversation; old ones stay in history. */
    const val FRESH_AFTER_MS = 30 * 60 * 1000L

    val state = MutableStateFlow(SetupChatViewModel.UiState())

    private val _history = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val history: StateFlow<List<ConversationSummary>> = _history.asStateFlow()

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var app: Context
    private lateinit var store: ConversationStore
    private var createdAt = 0L
    private var updatedAt = 0L
    private var hermesSession: String? = null
    private var title: String? = null
    private var titleAsked = false

    @Volatile private var nextProposalId = 0L
    private var saveJob: Job? = null

    // Screens showing a call: the chat (while visible) and the call over the
    // home screen. The call ends shortly after the last one goes, so a mic is
    // never left open in the background, but moving between them keeps it.
    private var holders = 0

    fun hold() {
        holders++
    }

    fun release() {
        holders = (holders - 1).coerceAtLeast(0)
        scope.launch {
            delay(2_000)
            if (holders == 0) DogoCall.stop()
        }
    }

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        store = ConversationStore(File(app.filesDir, "conversations"))
        startNew()
        scope.launch {
            val list = withContext(Dispatchers.IO) { store.list() }
            _history.value = list
            // Coming back within the half hour carries on where you left off,
            // unless something has already been said in the new one.
            val latest = list.firstOrNull() ?: return@launch
            if (System.currentTimeMillis() - latest.updatedAt >= FRESH_AFTER_MS) return@launch
            val c = withContext(Dispatchers.IO) { store.load(latest.id) } ?: return@launch
            if (state.value.messages.isEmpty() && !state.value.busy) show(c)
        }
    }

    /** Starts a new conversation if the open one has gone quiet. Used when the light is tapped. */
    fun freshIfIdle() {
        if (state.value.messages.isNotEmpty() && System.currentTimeMillis() - updatedAt > FRESH_AFTER_MS) newConversation()
    }

    fun newConversation() {
        if (state.value.busy) return
        if (state.value.messages.isEmpty()) return
        DogoCall.stop()
        flush()
        startNew()
    }

    fun open(id: String) {
        if (id == state.value.conversationId || state.value.busy) return
        scope.launch {
            val c = withContext(Dispatchers.IO) { store.load(id) } ?: return@launch
            // A call keeps talking about the conversation it started in.
            DogoCall.stop()
            flush()
            show(c)
        }
    }

    private fun show(c: Conversation) {
        createdAt = c.createdAt
        updatedAt = c.updatedAt
        hermesSession = c.hermesSession
        title = c.title
        titleAsked = false
        nextProposalId = (c.messages.flatMap { it.proposals }.maxOfOrNull { it.id } ?: -1L) + 1
        state.update { it.copy(conversationId = c.id, messages = c.messages, title = c.title, status = null) }
        recheckBlocked()
    }

    fun delete(id: String) {
        if (id == state.value.conversationId) {
            if (state.value.busy) return
            DogoCall.stop()
            saveJob?.cancel()
            startNew()
        }
        scope.launch {
            withContext(NonCancellable + Dispatchers.IO) { store.delete(id) }
            refreshHistory()
        }
    }

    private fun startNew() {
        val now = System.currentTimeMillis()
        createdAt = now
        updatedAt = now
        hermesSession = null
        title = null
        titleAsked = false
        nextProposalId = 0
        state.update { it.copy(conversationId = ConversationStore.newId(now), messages = emptyList(), title = null, status = null) }
    }

    fun add(message: Message) {
        updatedAt = System.currentTimeMillis()
        state.update { it.copy(messages = it.messages + message) }
        persistSoon()
    }

    /** Takes back the last message if it is [message], e.g. when the agent couldn't answer it. */
    fun removeLast(message: Message) {
        state.update { s -> if (s.messages.lastOrNull() === message) s.copy(messages = s.messages.dropLast(1)) else s }
        persistSoon()
    }

    /** What the agent should see of this conversation. */
    fun turns(): List<ChatTurn> = ConversationCodec.turnsOf(state.value.messages)

    fun hermesSession(): String? = hermesSession

    /**
     * Gives a conversation a name. Blank clears it, so the first message is used again.
     * Works on the open conversation or any saved one.
     */
    fun rename(id: String, newTitle: String) {
        val clean = newTitle.trim().take(80).ifBlank { null }
        if (id == state.value.conversationId) {
            title = clean
            titleAsked = clean != null
            state.update { it.copy(title = clean) }
            persistSoon()
            return
        }
        scope.launch {
            withContext(NonCancellable + Dispatchers.IO) { store.load(id)?.let { store.save(it.copy(title = clean)) } }
            refreshHistory()
        }
    }

    /**
     * After the first answer, asks [ask] for a short name. A name the user chose is
     * never replaced, and a failed or odd reply leaves the first-message title.
     */
    fun nameIfNeeded(ask: suspend (system: String, turns: List<app.dogo.launcher.agent.backend.ChatTurn>) -> String?) {
        val s = state.value
        if (title != null || titleAsked || s.messages.any { it.notificationContext } || s.messages.count { !it.note } < 2) return
        titleAsked = true
        val id = s.conversationId
        val turns = TitleMaker.turns(s.messages)
        scope.launch {
            val named = runCatching { ask(TitleMaker.SYSTEM, turns) }.getOrNull()?.let(TitleMaker::clean) ?: return@launch
            // The user may have switched conversations or renamed it while this ran.
            if (state.value.conversationId == id && title == null) {
                title = named
                state.update { it.copy(title = named) }
                persistSoon()
            }
        }
    }

    fun rememberHermesSession(id: String?) {
        if (id == null || id == hermesSession) return
        hermesSession = id
        persistSoon()
    }

    /** Saves a photo's thumbnail beside the conversation. Call off the main thread. */
    fun savePhoto(thumb: Bitmap): String? = runCatching {
        val name = "${state.value.conversationId}-${System.currentTimeMillis()}.jpg"
        store.photo(name).outputStream().use { thumb.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        name
    }.getOrNull()

    fun photoFile(name: String): File = store.photo(name)

    // ---- proposed changes ---------------------------------------------------

    /** Wraps [actions] as proposals, each checked against Dogo Options and what access the phone has. */
    suspend fun proposalsFor(actions: List<Action>): List<Proposal> {
        if (actions.isEmpty() || state.value.messages.any { it.notificationContext }) return emptyList()
        val options = OptionsStore(app).load()
        val availability = SystemAccess(app).availability()
        return actions.map { Proposal(nextProposalId++, it, stateFor(it, options, availability)) }
    }

    /** Carries out an approved proposal, then tells a live call how it went. */
    fun approve(id: Long, onDone: ((ActionApplier.Outcome) -> Unit)? = null) {
        if (state.value.messages.any { it.notificationContext }) return
        val proposal = find(id) ?: return
        if (proposal.state !is ActionState.Ready) return
        if (!OptionsStore(app).load().isAllowed(proposal.action)) {
            setProposal(id, ActionState.Disallowed)
            return
        }
        setProposal(id, ActionState.Applying)
        scope.launch {
            val outcome = ActionApplier(app, SystemAccess(app)).apply(proposal.action)
            setProposal(id, if (outcome.ok) ActionState.Applied(outcome.message) else ActionState.Failed(outcome.message))
            DogoCall.tell(if (outcome.ok) "The user applied a change: ${outcome.message}" else "A change failed: ${outcome.message}")
            onDone?.invoke(outcome)
        }
    }

    fun dismiss(id: Long) {
        val proposal = find(id) ?: return
        if (proposal.state is ActionState.Applying || proposal.state is ActionState.Applied) return
        setProposal(id, ActionState.Dismissed)
    }

    fun find(id: Long): Proposal? = state.value.messages.asSequence().flatMap { it.proposals }.firstOrNull { it.id == id }

    /** Re-checks proposals that were waiting for access, e.g. after Shizuku was set up. */
    fun recheckBlocked() {
        if (!::app.isInitialized) return
        scope.launch {
            val options = OptionsStore(app).load()
            val availability = SystemAccess(app).availability()
            state.update { s ->
                s.copy(
                    messages = s.messages.map { m ->
                        if (m.proposals.isEmpty()) {
                            m
                        } else {
                            m.copy(
                                proposals = m.proposals.map { p ->
                                    if (p.state is ActionState.Blocked || p.state is ActionState.Ready || p.state is ActionState.Disallowed) {
                                        p.copy(state = stateFor(p.action, options, availability))
                                    } else {
                                        p
                                    }
                                },
                            )
                        }
                    },
                )
            }
        }
    }

    private fun stateFor(action: Action, options: AgentOptions, availability: PrivilegePlanner.Availability): ActionState {
        if (!options.isAllowed(action)) return ActionState.Disallowed
        return when (val plan = PrivilegePlanner.plan(action, availability, app.packageName)) {
            is PrivilegePlanner.Plan.Blocked -> ActionState.Blocked(plan.hint)
            PrivilegePlanner.Plan.InLauncher -> ActionState.Ready("Launcher only")
            is PrivilegePlanner.Plan.WithPermission -> ActionState.Ready("Granted permission")
            is PrivilegePlanner.Plan.ViaShell -> ActionState.Ready(plan.tier.title)
            is PrivilegePlanner.Plan.ViaPlugin -> ActionState.Ready("Plugin ${plan.pluginId}")
        }
    }

    private fun setProposal(id: Long, value: ActionState) {
        state.update { ui ->
            ui.copy(
                messages = ui.messages.map { m ->
                    if (m.proposals.none { it.id == id }) m else m.copy(proposals = m.proposals.map { if (it.id == id) it.copy(state = value) else it })
                },
            )
        }
        persistSoon()
    }

    // ---- saving --------------------------------------------------------------

    private fun snapshot(): Conversation? {
        val s = state.value
        if (s.messages.isEmpty()) return null
        return Conversation(s.conversationId, s.messages, createdAt, updatedAt, hermesSession, title)
    }

    private fun persistSoon() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(400)
            val c = snapshot() ?: return@launch
            withContext(NonCancellable + Dispatchers.IO) { store.save(c) }
            refreshHistory()
        }
    }

    /** Saves now, e.g. before switching conversations or when a call ends. */
    fun flush() {
        if (!::app.isInitialized) return
        saveJob?.cancel()
        val c = snapshot() ?: return
        scope.launch {
            withContext(NonCancellable + Dispatchers.IO) { store.save(c) }
            refreshHistory()
        }
    }

    private suspend fun refreshHistory() {
        _history.value = withContext(Dispatchers.IO) { store.list() }
    }
}
