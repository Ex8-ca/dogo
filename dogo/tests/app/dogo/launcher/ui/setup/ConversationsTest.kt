/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ui.setup

import app.dogo.launcher.agent.Action
import app.dogo.launcher.agent.backend.ChatTurn
import app.dogo.launcher.ui.setup.SetupChatViewModel.ActionState
import app.dogo.launcher.ui.setup.SetupChatViewModel.Message
import app.dogo.launcher.ui.setup.SetupChatViewModel.Proposal
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.json.JSONObject
import org.junit.Test

class ConversationsTest {

    private val zone = ZoneId.of("America/Chicago")
    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private val sample = Conversation(
        id = "c1",
        createdAt = 1_000,
        updatedAt = 2_000,
        hermesSession = "sess-42",
        messages = listOf(
            Message("what's the hostname of my hermes computer?", fromUser = true, at = 1_000, spoken = true),
            Message("Asked Hermes to look into it", fromUser = false, at = 1_100, note = true),
            Message("It's ai5080.", fromUser = false, at = 1_200, spoken = true),
            Message("now make it dark", fromUser = true, at = 1_300),
            Message(
                "Here you go.\n{\"action\":\"set_theme\",\"params\":{\"theme\":\"tokyo-night\"}}",
                fromUser = false,
                at = 1_400,
                proposals = listOf(Proposal(7, Action.SetTheme("tokyo-night"), ActionState.Applied("Theme set"))),
            ),
        ),
    )

    @Test
    fun `a conversation survives being saved and read back`() {
        val back = ConversationCodec.fromJson(JSONObject(ConversationCodec.toJson(sample).toString()))
        assertThat(back.id).isEqualTo("c1")
        assertThat(back.hermesSession).isEqualTo("sess-42")
        assertThat(back.messages.map { it.text }).isEqualTo(sample.messages.map { it.text })
        assertThat(back.messages.map { it.spoken }).isEqualTo(listOf(true, false, true, false, false))
        assertThat(back.messages[1].note).isTrue()
        val p = back.messages[4].proposals.single()
        assertThat(p.id).isEqualTo(7)
        assertThat(p.action).isEqualTo(Action.SetTheme("tokyo-night"))
        assertThat(p.state).isEqualTo(ActionState.Applied("Theme set"))
    }

    @Test
    fun `a change interrupted mid-apply is offered again after a restart`() {
        val c = sample.copy(messages = listOf(Message("x", false, proposals = listOf(Proposal(1, Action.HideApp("a.b"), ActionState.Applying)))))
        val back = ConversationCodec.fromJson(JSONObject(ConversationCodec.toJson(c).toString()))
        assertThat(back.messages.single().proposals.single().state).isInstanceOf(ActionState.Ready::class.java)
    }

    @Test
    fun `the title is the first thing asked, shortened at a word`() {
        assertThat(ConversationCodec.titleOf(sample.messages)).isEqualTo("What's the hostname of my hermes computer")
        val long = listOf(Message("please set up a really cool dark home screen with a clock widget and music", true))
        val title = ConversationCodec.titleOf(long)
        assertThat(title).endsWith("…")
        assertThat(title.length).isAtMost(43)
        assertThat(title).doesNotContain("  ")
        assertThat(ConversationCodec.titleOf(emptyList())).isEqualTo("New chat")
        assertThat(ConversationCodec.titleOf(listOf(Message(PHOTO_PROMPT, true, photoFile = "p.jpg")))).isEqualTo("Copy a phone's look")
    }

    @Test
    fun `the summary skips status notes, marks voice and searches every word`() {
        val s = ConversationCodec.summaryOf(sample)
        assertThat(s.messageCount).isEqualTo(4)
        assertThat(s.hasVoice).isTrue()
        assertThat(s.preview).isEqualTo("Here you go.")
        assertThat(s.matches("ai5080")).isTrue()
        assertThat(s.matches("HOSTNAME dark")).isTrue()
        assertThat(s.matches("hostname wallpaper")).isFalse()
        assertThat(s.matches("  ")).isTrue()
    }

    @Test
    fun `the agent sees typed and spoken turns, with voice changes written as actions`() {
        val spokenChange = Message(
            "Proposed change:",
            fromUser = false,
            spoken = true,
            proposals = listOf(Proposal(3, Action.HideApp("com.x"), ActionState.Dismissed)),
        )
        val turns = ConversationCodec.turnsOf(sample.messages + spokenChange)
        assertThat(turns.map { it.role }).containsExactly(
            ChatTurn.Role.USER,
            ChatTurn.Role.ASSISTANT,
            ChatTurn.Role.USER,
            ChatTurn.Role.ASSISTANT,
            ChatTurn.Role.ASSISTANT,
        ).inOrder()
        assertThat(turns.none { "Asked Hermes" in it.text }).isTrue()
        // A typed reply already carries its JSON; it isn't repeated.
        assertThat(turns[3].text.split("\"action\"").size).isEqualTo(2)
        assertThat(turns[4].text).contains("\"hide_app\"")
        assertThat(turns[4].text).contains("the user said no")
    }

    @Test
    fun `dates read like a phone's messages app`() {
        val now = at(2026, 11, 22, 15)
        val en = Locale.US
        assertThat(ConversationCodec.whenLabel(at(2026, 11, 22, 9, 5), now, zone, en)).isEqualTo("9:05 AM")
        assertThat(ConversationCodec.whenLabel(at(2026, 11, 21), now, zone, en)).isEqualTo("Yesterday")
        assertThat(ConversationCodec.whenLabel(at(2026, 11, 18), now, zone, en)).isEqualTo("Wednesday")
        assertThat(ConversationCodec.whenLabel(at(2026, 10, 3), now, zone, en)).isEqualTo("Oct 3")
        assertThat(ConversationCodec.whenLabel(at(2025, 10, 3), now, zone, en)).isEqualTo("Oct 3, 2025")
        assertThat(ConversationCodec.sectionOf(at(2026, 11, 22, 1), now, zone)).isEqualTo("Today")
        assertThat(ConversationCodec.sectionOf(at(2026, 11, 21), now, zone)).isEqualTo("Yesterday")
        assertThat(ConversationCodec.sectionOf(at(2026, 11, 17), now, zone)).isEqualTo("This week")
        assertThat(ConversationCodec.sectionOf(at(2026, 11, 1), now, zone)).isEqualTo("Earlier")
    }

    @Test
    fun `the store lists newest first, skips empty and broken files, and deletes`() {
        val dir = Files.createTempDirectory("convs").toFile()
        val store = ConversationStore(dir)
        store.save(sample)
        store.save(sample.copy(id = "c2", updatedAt = 9_000, messages = listOf(Message("add a clock", true))))
        store.save(sample.copy(id = "c3", messages = emptyList()))
        dir.resolve("junk.json").writeText("{not json")
        assertThat(store.list().map { it.id }).containsExactly("c2", "c1").inOrder()
        assertThat(store.load("c1")?.messages?.size).isEqualTo(5)

        dir.resolve("c2-1.jpg").writeText("img")
        store.save(sample.copy(id = "c2", updatedAt = 9_000, messages = listOf(Message("add a clock", true, photoFile = "c2-1.jpg"))))
        store.delete("c2")
        assertThat(store.list().map { it.id }).containsExactly("c1")
        assertThat(dir.resolve("c2-1.jpg").exists()).isFalse()
        dir.deleteRecursively()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ids can't reach outside the folder`() {
        ConversationStore(Files.createTempDirectory("convs").toFile()).load("../shared_prefs/x")
    }

    @Test
    fun `a chosen name wins over the first message, survives saving, and is searchable`() {
        val named = sample.copy(title = "Hostname check")
        val back = ConversationCodec.fromJson(JSONObject(ConversationCodec.toJson(named).toString()))
        assertThat(back.title).isEqualTo("Hostname check")
        val summary = ConversationCodec.summaryOf(back)
        assertThat(summary.title).isEqualTo("Hostname check")
        assertThat(summary.named).isTrue()
        assertThat(summary.matches("hostname check")).isTrue()
        // No chosen name: the first message is used and nothing is stored as custom.
        val plain = ConversationCodec.fromJson(JSONObject(ConversationCodec.toJson(sample).toString()))
        assertThat(plain.title).isNull()
        assertThat(ConversationCodec.summaryOf(plain).named).isFalse()
    }

    @Test
    fun `model titles are cleaned up, and unusable ones are dropped`() {
        assertThat(TitleMaker.clean("\"Setting up a dark home screen.\"")).isEqualTo("Setting up a dark home screen")
        assertThat(TitleMaker.clean("Title: hostname of Hermes")).isEqualTo("Hostname of Hermes")
        assertThat(TitleMaker.clean("\n\n**Clock widget setup**\nSecond line ignored")).isEqualTo("Clock widget setup")
        assertThat(TitleMaker.clean("")).isNull()
        assertThat(TitleMaker.clean("Sorry, I can't help with that")).isNull()
        assertThat(TitleMaker.clean("x".repeat(80))).isNull()
        assertThat(TitleMaker.clean("a b c d e f g h i j k")).isNull()
    }

    @Test
    fun `the naming request holds the first exchange without photos or JSON`() {
        val turns = TitleMaker.turns(sample.messages)
        assertThat(turns).hasSize(1)
        val text = turns.single().text
        assertThat(text).contains("User: what's the hostname")
        assertThat(text).contains("Assistant: It's ai5080.")
        assertThat(text).doesNotContain("Asked Hermes")
        assertThat(text.length).isLessThan(1_800)
    }
}
