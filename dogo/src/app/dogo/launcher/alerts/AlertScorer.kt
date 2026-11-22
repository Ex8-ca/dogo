/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.alerts

import android.content.Context
import app.dogo.launcher.agent.AgentOptions
import app.dogo.launcher.agent.backend.AgentBackends
import app.dogo.launcher.agent.backend.ChatTurn
import app.dogo.launcher.agent.backend.ReplyOptions
import app.dogo.launcher.agent.backend.ToolFreePolicy
import app.dogo.launcher.ui.options.OptionsStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

internal object AlertScorer {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    /** Does not include credentials. A changed address/model requires fresh setup consent. */
    fun destination(options: AgentOptions): String {
        val agent = options.resolvedAgent()
        return "${agent.preset.id}|${agent.baseUrl}|${agent.model}|${options.modelId.orEmpty()}"
    }

    fun supported(options: AgentOptions): Boolean = ToolFreePolicy.supports(options.agentId, options.resolvedAgent().baseUrl)

    fun disclosure(options: AgentOptions): String {
        val agent = options.resolvedAgent()
        return if (options.isOnDevice) {
            "On-device model (${options.modelId ?: "phone recommendation"})"
        } else {
            "${agent.preset.displayName} · ${agent.baseUrl.ifBlank { "selected CLI in Termux" }} · ${agent.model}"
        }
    }

    suspend fun classify(context: Context, options: AgentOptions, candidate: AlertCandidate, stillAllowed: () -> Boolean): AlertScore? = withContext(Dispatchers.IO) {
        check(destination(OptionsStore(context).load()) == destination(options)) { "Agent changed" }
        check(withContext(Dispatchers.Main.immediate) { stillAllowed() }) { "Capture consent changed" }
        // Fail closed for agent servers and arbitrary custom endpoints. Native
        // inference also waits until it supports cancellation and a hard budget.
        check(supported(options)) { ToolFreePolicy.UNAVAILABLE }
        val backend = checkNotNull(AgentBackends.remote(options.resolvedAgent(), client)) { "Selected agent unavailable" }
        val raw = backend.reply(
            AlertClassification.SYSTEM,
            listOf(ChatTurn(ChatTurn.Role.USER, AlertClassification.input(candidate))),
            ReplyOptions(effort = "low"),
        ).getOrThrow()
        AlertClassification.parse(raw)
    }
}
