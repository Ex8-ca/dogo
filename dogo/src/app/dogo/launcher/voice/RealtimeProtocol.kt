/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.voice

import app.dogo.launcher.agent.Action
import app.dogo.launcher.agent.ActionExecutor
import org.json.JSONArray
import org.json.JSONObject

/**
 * The OpenAI Realtime protocol as Dogo uses it, with no Android types so it
 * can be unit tested. [VoiceSession] does the audio and the socket.
 *
 * Changes come back as calls to one tool, `propose_changes`, whose actions use
 * the same JSON shape as the text chat; they become ordinary proposals the
 * user approves.
 */
object RealtimeProtocol {
    const val SAMPLE_RATE = 24_000
    const val TOOL = "propose_changes"

    /** Hands real work (web, files, memory, calendar...) to Hermes, like Hermes Vessel. */
    const val HANDOFF_TOOL = "use_tools"
    const val DEFAULT_MODEL = "gpt-realtime-2.1"
    const val DEFAULT_VOICE = "marin"
    val VOICES = listOf("marin", "cedar", "alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse")

    fun url(model: String) = "wss://api.openai.com/v1/realtime?model=$model"

    /** Spoken replies should be short; the details are on the cards. */
    private const val VOICE_RULES = """

You are talking out loud. Keep replies to one or two short sentences, in a
friendly, natural voice. Never read JSON, package names or lists aloud.
To change anything, call the propose_changes tool with actions in the JSON
shape described above. The changes appear as cards the user approves, so say
something like "I've put that up for you to approve", never that it is done.
When the request is clear, propose it straight away instead of asking
follow-up questions. If the user approves or applies a change, you'll be told."""

    private const val HANDOFF_RULES = """

You also have use_tools: your own tools on the user's computer (files, the web,
memory of past conversations, the calendar, smart home, code). For anything
that needs them, or facts you are not sure of, call use_tools with one
complete, self-contained request. Say a very short line like "One sec" first.
The work is yours: say "I checked" or "I found". Tell them the gist when the
result comes back, and never make results up."""

    fun sessionUpdate(instructions: String, voice: String, handoff: Boolean = false): JSONObject = JSONObject()
        .put("type", "session.update")
        .put(
            "session",
            JSONObject()
                .put("type", "realtime")
                .put("instructions", instructions + VOICE_RULES + if (handoff) HANDOFF_RULES else "")
                .put("output_modalities", JSONArray().put("audio"))
                .put(
                    "audio",
                    JSONObject()
                        .put(
                            "input",
                            JSONObject()
                                .put("format", pcmFormat())
                                .put("noise_reduction", JSONObject().put("type", "near_field"))
                                .put("transcription", JSONObject().put("model", "gpt-4o-mini-transcribe"))
                                .put("turn_detection", JSONObject().put("type", "semantic_vad").put("eagerness", "auto")),
                        )
                        .put("output", JSONObject().put("format", pcmFormat()).put("voice", voice)),
                )
                .put("tools", JSONArray().put(proposeTool()).apply { if (handoff) put(handoffTool()) })
                .put("tool_choice", "auto"),
        )

    private fun handoffTool() = JSONObject()
        .put("type", "function")
        .put("name", HANDOFF_TOOL)
        .put(
            "description",
            "Use your own tools: files, the web, memory, the calendar, smart home, code. " +
                "Describe the task as one complete instruction. It can take from seconds to minutes.",
        )
        .put(
            "parameters",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put("request", JSONObject().put("type", "string").put("description", "The task, with all the context needed.")))
                .put("required", JSONArray().put("request")),
        )

    private fun pcmFormat() = JSONObject().put("type", "audio/pcm").put("rate", SAMPLE_RATE)

    private fun proposeTool() = JSONObject()
        .put("type", "function")
        .put("name", TOOL)
        .put("description", "Propose one or more phone or launcher changes. The user sees each as a card and approves it.")
        .put(
            "parameters",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject().put(
                        "actions",
                        JSONObject()
                            .put("type", "array")
                            .put(
                                "items",
                                JSONObject()
                                    .put("type", "object")
                                    .put(
                                        "properties",
                                        JSONObject()
                                            .put("action", JSONObject().put("type", "string").put("description", "e.g. set_theme, hide_app, arrange_ring"))
                                            .put("params", JSONObject().put("type", "object")),
                                    )
                                    .put("required", JSONArray().put("action").put("params")),
                            ),
                    ),
                )
                .put("required", JSONArray().put("actions")),
        )

    fun appendAudio(base64: String): JSONObject = JSONObject().put("type", "input_audio_buffer.append").put("audio", base64)

    fun toolResult(callId: String, output: String): List<JSONObject> = listOf(
        JSONObject().put("type", "conversation.item.create").put(
            "item",
            JSONObject().put("type", "function_call_output").put("call_id", callId).put("output", output),
        ),
        JSONObject().put("type", "response.create"),
    )

    /** Tells the model something happened (e.g. a change was applied), without asking it to speak. */
    fun note(text: String): JSONObject = JSONObject().put("type", "conversation.item.create").put(
        "item",
        JSONObject().put("type", "message").put("role", "user").put(
            "content",
            JSONArray().put(JSONObject().put("type", "input_text").put("text", "[Dogo] $text")),
        ),
    )

    fun cancelResponse(): JSONObject = JSONObject().put("type", "response.cancel")

    /** What a server event means for Dogo. Unknown events are [Event.Ignored]. */
    sealed interface Event {
        data object Ready : Event
        data object UserStartedSpeaking : Event
        data object UserStoppedSpeaking : Event
        data class UserSaid(val text: String) : Event
        data class Audio(val base64: String) : Event
        data class AssistantSaid(val text: String) : Event

        /** Part of what the voice is saying right now, for live captions. */
        data class AssistantDelta(val text: String) : Event

        /** The voice wants Hermes to do [request]. Answer with [toolResult]. */
        data class UseTools(val callId: String, val request: String) : Event
        data class ProposeChanges(val callId: String, val actions: List<Action>, val rejected: Int) : Event
        data object ResponseDone : Event
        data class Failed(val message: String) : Event
        data object Ignored : Event
    }

    fun parse(text: String): Event {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return Event.Ignored
        return when (m.optString("type")) {
            "session.updated" -> Event.Ready

            "input_audio_buffer.speech_started" -> Event.UserStartedSpeaking

            "input_audio_buffer.speech_stopped" -> Event.UserStoppedSpeaking

            "conversation.item.input_audio_transcription.completed" -> m.optString("transcript").trim().takeIf { it.isNotEmpty() }?.let { Event.UserSaid(it) } ?: Event.Ignored

            "response.output_audio.delta" -> m.optString("delta").takeIf { it.isNotEmpty() }?.let { Event.Audio(it) } ?: Event.Ignored

            "response.output_audio_transcript.delta" -> m.optString("delta").takeIf { it.isNotEmpty() }?.let { Event.AssistantDelta(it) } ?: Event.Ignored

            "response.output_audio_transcript.done" -> m.optString("transcript").trim().takeIf { it.isNotEmpty() }?.let { Event.AssistantSaid(it) } ?: Event.Ignored

            "response.function_call_arguments.done" -> when (m.optString("name")) {
                TOOL -> proposeChanges(m)

                HANDOFF_TOOL -> {
                    val request = runCatching { JSONObject(m.optString("arguments")).optString("request") }.getOrNull()?.trim().orEmpty()
                    if (request.isEmpty()) Event.Ignored else Event.UseTools(m.optString("call_id"), request)
                }

                else -> Event.Ignored
            }

            "response.done" -> Event.ResponseDone

            "error" -> {
                val e = m.optJSONObject("error")
                // Cancelling when nothing is playing is harmless.
                if (e?.optString("code") == "response_cancel_not_active") Event.Ignored else Event.Failed(e?.optString("message")?.ifBlank { null } ?: "The voice service reported an error.")
            }

            else -> Event.Ignored
        }
    }

    private fun proposeChanges(m: JSONObject): Event {
        val callId = m.optString("call_id")
        val args = runCatching { JSONObject(m.optString("arguments")) }.getOrNull()
        val items = args?.optJSONArray("actions") ?: JSONArray()
        val actions = (0 until items.length()).mapNotNull { items.optJSONObject(it) }.map { ActionExecutor.parseAction(it.toString()) }
        val good = actions.filter { it != Action.Unknown }
        return Event.ProposeChanges(callId, good, actions.size - good.size)
    }

    /** What to tell the model after its proposals were turned into cards. */
    fun proposalResult(shown: Int, rejected: Int): String = JSONObject()
        .put("status", if (shown > 0) "shown_to_user_for_approval" else "nothing_shown")
        .put("cards_shown", shown)
        .put("not_understood", rejected)
        .toString()
}
