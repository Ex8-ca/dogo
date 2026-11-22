/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ui.setup

import app.dogo.launcher.agent.ActionExecutor
import app.dogo.launcher.agent.backend.ChatTurn
import app.dogo.launcher.ui.setup.SetupChatViewModel.ActionState
import app.dogo.launcher.ui.setup.SetupChatViewModel.Message
import app.dogo.launcher.ui.setup.SetupChatViewModel.Proposal
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** One saved conversation with Dogo, typed and spoken turns together. */
data class Conversation(
    val id: String,
    val messages: List<Message>,
    val createdAt: Long,
    val updatedAt: Long,
    /** Hermes's own session for this conversation, so a resumed call keeps its memory. */
    val hermesSession: String? = null,
    /** A name from the user or the model. Null means "work it out from the first message". */
    val title: String? = null,
)

/** What the history list shows for a conversation. */
data class ConversationSummary(
    val id: String,
    val title: String,
    val preview: String,
    val updatedAt: Long,
    val messageCount: Int,
    val hasVoice: Boolean,
    /** The title was chosen (by the user or the model), not worked out from the first message. */
    val named: Boolean = false,
    /** Lower-case text of the whole conversation, for search. */
    val searchText: String,
    /** Contains notification text; kept out of anything sent to another model. */
    val restricted: Boolean = false,
) {
    fun matches(query: String): Boolean = query.isBlank() || query.trim().lowercase().split(Regex("\\s+")).all { it in searchText }
}

/**
 * Turns conversations into JSON and back, and works out titles and the
 * history the agent sees. Plain Kotlin, so it is unit tested without a phone.
 */
object ConversationCodec {
    private const val VERSION = 1

    fun toJson(c: Conversation): JSONObject = JSONObject()
        .put("v", VERSION)
        .put("id", c.id)
        .put("created", c.createdAt)
        .put("updated", c.updatedAt)
        .put("title", displayTitle(c.title, c.messages))
        .apply {
            c.hermesSession?.let { put("hermes_session", it) }
            // "title" above is only for people reading the file; this is the one that counts.
            c.title?.let { put("custom_title", it) }
        }
        .put("messages", JSONArray().apply { c.messages.forEach { put(messageJson(it)) } })

    fun fromJson(json: JSONObject): Conversation {
        val list = json.optJSONArray("messages") ?: JSONArray()
        val messages = (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let(::message) }
        return Conversation(
            id = json.getString("id"),
            messages = messages,
            createdAt = json.optLong("created"),
            updatedAt = json.optLong("updated"),
            hermesSession = json.optString("hermes_session").ifBlank { null },
            title = json.optString("custom_title").ifBlank { null },
        )
    }

    private fun messageJson(m: Message) = JSONObject()
        .put("user", m.fromUser)
        .put("text", m.text)
        .put("at", m.at)
        .apply {
            if (m.spoken) put("spoken", true)
            if (m.notificationContext) put("notification_context", true)
            if (m.note) put("note", true)
            m.photoFile?.let { put("photo", it) }
            if (m.proposals.isNotEmpty()) put("proposals", JSONArray().apply { m.proposals.forEach { put(proposalJson(it)) } })
        }

    private fun message(o: JSONObject): Message {
        val props = o.optJSONArray("proposals") ?: JSONArray()
        return Message(
            text = o.optString("text"),
            fromUser = o.optBoolean("user"),
            proposals = (0 until props.length()).mapNotNull { i -> props.optJSONObject(i)?.let(::proposal) },
            at = o.optLong("at"),
            spoken = o.optBoolean("spoken"),
            notificationContext = o.optBoolean("notification_context") || o.optString("text").contains("untrusted notification data"),
            note = o.optBoolean("note"),
            photoFile = o.optString("photo").ifBlank { null },
        )
    }

    private fun proposalJson(p: Proposal): JSONObject {
        val (kind, detail) = when (val s = p.state) {
            is ActionState.Ready -> "ready" to s.route

            ActionState.Disallowed -> "disallowed" to ""

            is ActionState.Blocked -> "blocked" to s.hint

            // An apply cut short by the launcher closing is offered again.
            ActionState.Applying -> "ready" to ""

            is ActionState.Applied -> "applied" to s.message

            is ActionState.Failed -> "failed" to s.message

            ActionState.Dismissed -> "dismissed" to ""
        }
        return JSONObject().put("id", p.id).put("action", ActionExecutor.toJson(p.action)).put("state", kind).put("detail", detail)
    }

    private fun proposal(o: JSONObject): Proposal? {
        val action = ActionExecutor.parseAction(o.optString("action"))
        if (action == app.dogo.launcher.agent.Action.Unknown) return null
        val detail = o.optString("detail")
        val state = when (o.optString("state")) {
            "applied" -> ActionState.Applied(detail)
            "failed" -> ActionState.Failed(detail)
            "dismissed" -> ActionState.Dismissed
            "disallowed" -> ActionState.Disallowed
            "blocked" -> ActionState.Blocked(detail)
            else -> ActionState.Ready(detail)
        }
        return Proposal(o.optLong("id"), action, state)
    }

    /** A chosen name if there is one, else the first thing asked. */
    fun displayTitle(custom: String?, messages: List<Message>): String = custom?.takeIf { it.isNotBlank() } ?: titleOf(messages)

    /** The first thing the user asked, shortened; that's how people remember a chat. */
    fun titleOf(messages: List<Message>): String {
        val first = messages.firstOrNull { it.fromUser && !it.note } ?: return "New chat"
        if (first.photoFile != null || first.photo != null) {
            if (first.text.isBlank() || first.text == PHOTO_PROMPT) return "Copy a phone's look"
        }
        val text = first.text.replace(Regex("\\s+"), " ").trim().trimEnd('?', '.', '!')
        if (text.isEmpty()) return "New chat"
        if (text.length <= 42) return text.replaceFirstChar { it.uppercase() }
        val cut = text.take(42).substringBeforeLast(' ').ifBlank { text.take(42) }
        return cut.replaceFirstChar { it.uppercase() } + "…"
    }

    fun summaryOf(c: Conversation): ConversationSummary {
        val shown = c.messages.filterNot { it.note }
        val last = shown.lastOrNull()
        val preview = last?.let { (if (it.fromUser) "You: " else "") + stripActionJson(it.text).replace(Regex("\\s+"), " ") }.orEmpty().take(90)
        return ConversationSummary(
            id = c.id,
            title = displayTitle(c.title, c.messages),
            named = !c.title.isNullOrBlank(),
            preview = preview,
            updatedAt = c.updatedAt,
            messageCount = shown.size,
            hasVoice = c.messages.any { it.spoken },
            searchText = (displayTitle(c.title, c.messages) + " " + c.messages.joinToString(" ") { stripActionJson(it.text) }).lowercase().take(8_000),
            restricted = c.messages.any { it.notificationContext },
        )
    }

    /**
     * The conversation as the agent should see it. Status notes are left out;
     * changes the voice proposed are written as the actions they were, so a
     * typed follow-up knows what was already offered.
     */
    fun turnsOf(messages: List<Message>): List<ChatTurn> = messages.filterNot { it.note }.mapNotNull { m ->
        val photo = if (m.photoFile != null || m.photo != null) "\n(The user sent a photo here.)" else ""
        val actions = if (m.proposals.isNotEmpty() && "\"action\"" !in m.text) {
            m.proposals.joinToString("\n", prefix = "\n") { p -> ActionExecutor.toJson(p.action) + outcome(p.state) }
        } else {
            ""
        }
        val text = (m.text + photo + actions).trim()
        if (text.isEmpty()) null else ChatTurn(if (m.fromUser) ChatTurn.Role.USER else ChatTurn.Role.ASSISTANT, text)
    }

    private fun outcome(s: ActionState) = when (s) {
        is ActionState.Applied -> "  (applied)"
        ActionState.Dismissed -> "  (the user said no)"
        is ActionState.Failed -> "  (failed: ${s.message})"
        else -> ""
    }

    /** "7:15 PM" today, "Yesterday", the weekday this week, else "Nov 3". */
    fun whenLabel(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val time = Instant.ofEpochMilli(at).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = time.toLocalDate()
        return when {
            day == today -> time.format(DateTimeFormatter.ofPattern("h:mm a", locale))
            day == today.minusDays(1) -> "Yesterday"
            day.isAfter(today.minusDays(7)) -> time.format(DateTimeFormatter.ofPattern("EEEE", locale))
            day.year == today.year -> time.format(DateTimeFormatter.ofPattern("MMM d", locale))
            else -> time.format(DateTimeFormatter.ofPattern("MMM d, yyyy", locale))
        }
    }

    /** Groups for the history list. */
    fun sectionOf(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when {
            day == today -> "Today"
            day == today.minusDays(1) -> "Yesterday"
            day.isAfter(today.minusDays(7)) -> "This week"
            else -> "Earlier"
        }
    }
}

/**
 * Conversations on disk, one JSON file each under [dir], with photo
 * thumbnails beside them. Nothing leaves the phone.
 */
class ConversationStore(private val dir: File) {

    init {
        dir.mkdirs()
    }

    fun list(): List<ConversationSummary> = files().mapNotNull { f -> read(f)?.let(ConversationCodec::summaryOf) }
        .filter { it.messageCount > 0 }
        .sortedByDescending { it.updatedAt }

    fun load(id: String): Conversation? = read(fileFor(id))

    fun save(c: Conversation) {
        if (c.messages.isEmpty()) return
        val target = fileFor(c.id)
        val tmp = File(dir, "${c.id}.json.tmp")
        tmp.writeText(ConversationCodec.toJson(c).toString())
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    fun delete(id: String) {
        val c = load(id)
        c?.messages?.mapNotNull { it.photoFile }?.forEach { File(dir, it).delete() }
        fileFor(id).delete()
    }

    fun photo(name: String): File = File(dir, name)

    private fun files() = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().toList()

    private fun fileFor(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "bad conversation id" }
        return File(dir, "$id.json")
    }

    private fun read(f: File): Conversation? = runCatching { ConversationCodec.fromJson(JSONObject(f.readText())) }.getOrNull()

    companion object {
        fun newId(now: Long = System.currentTimeMillis()): String = "c$now-${(1000..9999).random()}"
    }
}

/**
 * Asks the agent for a short name for a conversation, and cleans up what
 * comes back. Models like to add quotes, "Title:" and full stops.
 */
object TitleMaker {
    const val SYSTEM = "You name conversations. Reply with only a title of 3 to 5 words that says what the " +
        "conversation is about. No quotes, no full stop, no prefix. Use the user's language."

    /** The first exchange is enough; anything longer only costs tokens. */
    fun turns(messages: List<Message>): List<ChatTurn> {
        val shown = ConversationCodec.turnsOf(messages).take(4).map { it.copy(text = stripActionJson(it.text).take(400), images = emptyList()) }
        val transcript = shown.joinToString("\n") { (if (it.role == ChatTurn.Role.USER) "User: " else "Assistant: ") + it.text.replace(Regex("\\s+"), " ") }
        return listOf(ChatTurn(ChatTurn.Role.USER, "Name this conversation:\n\n$transcript"))
    }

    /** Null when the reply isn't usable as a name; the caller keeps the first-message title. */
    fun clean(raw: String): String? {
        var t = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        t = t.replace(Regex("^(?i)(title|name)\\s*[:：-]\\s*"), "")
        t = t.trim().trim('"', '\'', '“', '”', '‘', '’', '*', '#', '`', ' ').trimEnd('.', '。', '!', '?').trim()
        if (t.isEmpty() || t.length > 60) return null
        if (t.split(Regex("\\s+")).size > 9) return null
        // A refusal or an apology is not a name.
        if (Regex("^(?i)(sorry|i can.?t|i cannot|i'm sorry|as an ai)").containsMatchIn(t)) return null
        return t.replaceFirstChar { it.uppercase() }
    }
}
