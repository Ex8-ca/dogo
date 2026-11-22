/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.voice

import app.dogo.launcher.agent.Action
import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test

class RealtimeProtocolTest {

    @Test
    fun `session update asks for 24k pcm, the voice and the propose tool`() {
        val s = RealtimeProtocol.sessionUpdate("SYSTEM", "cedar").getJSONObject("session")
        assertThat(s.getString("type")).isEqualTo("realtime")
        assertThat(s.getString("instructions")).startsWith("SYSTEM")
        assertThat(s.getString("instructions")).contains("propose_changes")
        val audio = s.getJSONObject("audio")
        assertThat(audio.getJSONObject("input").getJSONObject("format").getInt("rate")).isEqualTo(24_000)
        assertThat(audio.getJSONObject("output").getString("voice")).isEqualTo("cedar")
        val tool = s.getJSONArray("tools").getJSONObject(0)
        assertThat(tool.getString("name")).isEqualTo(RealtimeProtocol.TOOL)
    }

    @Test
    fun `tool call becomes Dogo actions, junk is counted not kept`() {
        // The shape gpt-realtime actually sent in testing, whitespace and all.
        val args = """{ "actions": [ {"action":"set_theme","params":{"theme":"dark"}}, {"action":"hide_app","params":{"app_name":"Calculator"}}, {"action":"launch_rockets","params":{}} ] }"""
        val event = RealtimeProtocol.parse(
            JSONObject().put("type", "response.function_call_arguments.done").put("name", "propose_changes").put("call_id", "call_1").put("arguments", args).toString(),
        )
        event as RealtimeProtocol.Event.ProposeChanges
        assertThat(event.callId).isEqualTo("call_1")
        // hide_app without "package" still parses (to an empty package the applier rejects), launch_rockets doesn't.
        assertThat(event.actions).contains(Action.SetTheme("dark"))
        assertThat(event.rejected).isEqualTo(1)
    }

    @Test
    fun `server events map to what Dogo cares about`() {
        fun ev(type: String, extra: (JSONObject) -> Unit = {}) = RealtimeProtocol.parse(JSONObject().put("type", type).also(extra).toString())
        assertThat(ev("session.updated")).isEqualTo(RealtimeProtocol.Event.Ready)
        assertThat(ev("input_audio_buffer.speech_started")).isEqualTo(RealtimeProtocol.Event.UserStartedSpeaking)
        assertThat(ev("conversation.item.input_audio_transcription.completed") { it.put("transcript", " hide calculator ") })
            .isEqualTo(RealtimeProtocol.Event.UserSaid("hide calculator"))
        assertThat(ev("response.output_audio.delta") { it.put("delta", "AAAA") }).isEqualTo(RealtimeProtocol.Event.Audio("AAAA"))
        assertThat(ev("error") { it.put("error", JSONObject().put("message", "bad key")) }).isEqualTo(RealtimeProtocol.Event.Failed("bad key"))
        assertThat(ev("error") { it.put("error", JSONObject().put("code", "response_cancel_not_active")) }).isEqualTo(RealtimeProtocol.Event.Ignored)
        assertThat(ev("rate_limits.updated")).isEqualTo(RealtimeProtocol.Event.Ignored)
        assertThat(RealtimeProtocol.parse("not json")).isEqualTo(RealtimeProtocol.Event.Ignored)
        // Other tools are not ours to run.
        assertThat(ev("response.function_call_arguments.done") { it.put("name", "rm_rf") }).isEqualTo(RealtimeProtocol.Event.Ignored)
    }

    @Test
    fun `tool result answers the call and asks the model to carry on`() {
        val (item, create) = RealtimeProtocol.toolResult("call_9", RealtimeProtocol.proposalResult(2, 0))
        assertThat(item.getJSONObject("item").getString("call_id")).isEqualTo("call_9")
        assertThat(JSONObject(item.getJSONObject("item").getString("output")).getString("status")).isEqualTo("shown_to_user_for_approval")
        assertThat(create.getString("type")).isEqualTo("response.create")
    }

    @Test
    fun `voice config validates, round-trips and finds the token service next to Hermes`() {
        assertThat(VoiceConfig().problem()).isNotNull()
        val ok = VoiceConfig(tokenUrl = "https://h.ts.net:10000/dogo/realtime/token", serverKey = "k", voice = "ash")
        assertThat(ok.problem()).isNull()
        assertThat(VoiceConfig.fromJson(ok.toJson())).isEqualTo(ok)
        assertThat(VoiceConfig(tokenUrl = "http://insecure/token", serverKey = "k").problem()).isNotNull()
        assertThat(VoiceConfig(source = VoiceConfig.Source.API_KEY, apiKey = "sk-x").problem()).isNull()
        assertThat(VoiceConfig.fromJson("""{"voice":"darth"}""").voice).isEqualTo(RealtimeProtocol.DEFAULT_VOICE)
        assertThat(VoiceConfig.tokenUrlNextTo("https://ai5080.taila6e2e.ts.net:10000/v1")).isEqualTo("https://ai5080.taila6e2e.ts.net:10000/dogo/realtime/token")
        assertThat(VoiceConfig.tokenUrlNextTo("http://127.0.0.1:8642/v1")).isNull()
    }
}
