/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.agent.backend

import kotlin.coroutines.cancellation.CancellationException

/**
 * Per-request knobs. [effort] is "low", "medium" or "high", or null to leave
 * the server's default alone.
 */
data class ReplyOptions(val effort: String? = null)

/** One turn of the setup conversation. */
data class ChatTurn(val role: Role, val text: String) {
    enum class Role { USER, ASSISTANT }
}

/**
 * Something that can answer the setup conversation.
 *
 * Every backend receives the same system prompt and returns plain text. The
 * actions in that text go through the same parser and the same action gates no
 * matter which agent wrote them, so switching agents can never widen what Dogo
 * is allowed to change.
 */
interface AgentBackend {

    /** Shown in the chat header, e.g. "Hermes Agent · hermes-agent". */
    val label: String

    /** [turns] ends with the user's latest message. */
    suspend fun reply(system: String, turns: List<ChatTurn>, options: ReplyOptions = ReplyOptions()): Result<String>
}

/** A backend that can enumerate the models its server offers. */
interface ModelLister {
    suspend fun listModels(): Result<List<String>>
}

/** A failure with a message written for the person holding the phone. */
class AgentBackendException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Like [runCatching], but lets coroutine cancellation propagate. */
internal inline fun <T> resultOf(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
