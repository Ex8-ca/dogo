/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.agent.backend

import app.dogo.launcher.llm.ModelRunner
import app.dogo.launcher.ui.setup.SetupChatAgent
import app.dogo.launcher.ui.setup.SetupChatAgent.PhoneContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * The llama.cpp model loaded in [runner].
 *
 * Small local models do better with one flat prompt than with chat roles, so
 * the turns are rendered through [SetupChatAgent.buildPrompt]. [system] is
 * ignored: buildPrompt embeds the same system prompt, plus [phone].
 */
class OnDeviceBackend(
    private val runner: ModelRunner,
    private val phone: () -> PhoneContext? = { null },
) : AgentBackend {

    override val label: String
        get() = runner.loadedVariant?.displayName ?: AgentCatalog.ON_DEVICE.displayName

    override suspend fun reply(system: String, turns: List<ChatTurn>, options: ReplyOptions): Result<String> {
        val last = turns.lastOrNull()
        if (last == null || last.role != ChatTurn.Role.USER) {
            return Result.failure(IllegalArgumentException("The conversation must end with a user message."))
        }
        val history = turns.dropLast(1).map { turn ->
            (if (turn.role == ChatTurn.Role.USER) "user" else "assistant") to turn.text
        }
        val prompt = SetupChatAgent.buildPrompt(last.text, history, phone = phone())
        // generate() blocks for seconds to minutes; never on the main thread.
        return withContext(Dispatchers.IO) { runner.generate(prompt) }
    }
}

object AgentBackends {

    /** The remote backend for [agent], or null for the on-device agent. */
    fun remote(agent: ResolvedAgent, client: OkHttpClient = RemoteHttp.defaultClient): AgentBackend? = when (agent.preset.kind) {
        AgentKind.ON_DEVICE -> null
        AgentKind.OPENAI_COMPATIBLE -> OpenAiCompatibleBackend(agent, client)
        AgentKind.ANTHROPIC -> AnthropicBackend(agent, client)
    }
}
