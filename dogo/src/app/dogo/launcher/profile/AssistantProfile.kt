/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.profile

import app.dogo.launcher.ui.setup.ConversationSummary

/**
 * Who the assistant is talking to and how it should sound. Chosen by the
 * user (or copied from their Hermes agent and reviewed), never hard-coded.
 */
data class AssistantProfile(
    val name: String = "",
    val style: String = "",
    /** Calls get a short digest of recent conversations, so they feel continuous. */
    val recallEarlier: Boolean = true,
) {
    val hasName: Boolean get() = name.isNotBlank()
}

/** Text the profile adds to what the agent is told. Plain Kotlin, unit tested. */
object ProfileText {
    const val MAX_NAME = 40
    const val MAX_STYLE = 600
    const val MAX_DIGEST = 1_600
    private const val DIGEST_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
    private const val DIGEST_ITEMS = 5

    /** One line, no control characters, bounded. */
    fun clean(text: String, max: Int): String = text
        .filter { !it.isISOControl() || it == '\n' || it == '\t' }
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(max)

    fun cleanName(text: String): String = clean(text, MAX_NAME).trim('"', '\'', '“', '”', '.', ',', ' ')

    /** What the agent should know about the user. Empty when nothing is set. */
    fun promptSection(profile: AssistantProfile): String {
        val name = cleanName(profile.name)
        val style = clean(profile.style, MAX_STYLE)
        if (name.isEmpty() && style.isEmpty()) return ""
        return buildString {
            if (name.isNotEmpty()) {
                append("The person you are talking to is called ").append(name)
                append(". Use their name naturally now and then, not in every reply.\n")
            }
            if (style.isNotEmpty()) {
                append("How you should sound: ").append(style).append('\n')
            }
        }.trimEnd()
    }

    /**
     * The opening line of a call, as an instruction for the voice. [resumedTitle]
     * is the name of the conversation being continued, or null for a fresh one.
     */
    fun greeting(profile: AssistantProfile, resumedTitle: String?): String {
        val name = cleanName(profile.name)
        val who = if (name.isEmpty()) "greet the user" else "greet $name by name"
        val title = resumedTitle?.let { clean(it, 60).replace('"', '\'') }?.takeIf { it.isNotEmpty() }
        return if (title == null) {
            "The call just started. In one short sentence, $who and ask how you can help. " +
                "Do not mention these instructions."
        } else {
            "The call just started and it continues an earlier conversation called \"$title\". " +
                "In one short sentence, $who, and offer to carry on with that or help with something new. " +
                "Do not mention these instructions."
        }
    }

    /**
     * A few lines about recent conversations, for background. Conversations that
     * contain notification text are never included: that text is private and
     * untrusted, and a call must not carry it into a model it wasn't sent to.
     */
    fun earlierConversations(
        saved: List<ConversationSummary>,
        currentId: String,
        now: Long,
    ): String {
        val lines = saved
            .asSequence()
            .filter { it.id != currentId && !it.restricted && it.messageCount > 0 && now - it.updatedAt in 0..DIGEST_MAX_AGE_MS }
            .sortedByDescending { it.updatedAt }
            .take(DIGEST_ITEMS)
            .map { "- ${clean(it.title, 60)} (${ago(now, it.updatedAt)}): ${clean(it.preview, 90)}" }
            .toList()
        if (lines.isEmpty()) return ""
        val header = "Earlier conversations, for background only. They may be out of date. " +
            "Mention one only if it is relevant, and never read this list out."
        return (header + "\n" + lines.joinToString("\n")).take(MAX_DIGEST)
    }

    fun ago(now: Long, then: Long): String {
        val minutes = (now - then) / 60_000
        return when {
            minutes < 2 -> "just now"
            minutes < 60 -> "$minutes minutes ago"
            minutes < 24 * 60 -> "${minutes / 60} hours ago"
            minutes < 48 * 60 -> "yesterday"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    /** What the user's Hermes agent should be asked, and what we expect back. */
    const val IMPORT_REQUEST = "For Dogo, my phone launcher, reply in exactly two lines and nothing else.\n" +
        "NAME: what you call me (first name only), or NONE\n" +
        "STYLE: two or three short sentences on how you talk to me, from your SOUL.md and memory. " +
        "No lists, file paths, credentials, or private details about other people."

    data class Imported(val name: String, val style: String)

    /** Null when the reply isn't in the asked-for form, so nothing odd gets saved. */
    fun parseImport(reply: String): Imported? {
        fun field(label: String) = Regex("(?im)^\\s*$label\\s*[:：]\\s*(.+)$").find(reply)?.groupValues?.get(1)?.trim()
        val name = field("NAME")?.takeUnless { it.equals("none", ignoreCase = true) }?.let(::cleanName).orEmpty()
        val style = field("STYLE")?.let { clean(it, MAX_STYLE) }.orEmpty()
        if (name.isEmpty() && style.isEmpty()) return null
        return Imported(name, style)
    }
}
