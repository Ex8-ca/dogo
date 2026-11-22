/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.agent.backend

import java.net.URI

/** Only direct inference endpoints have a reviewed no-tools execution boundary. */
object ToolFreePolicy {
    fun supports(agentId: String, baseUrl: String): Boolean {
        val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return false
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443)) return false
        val host = when (agentId) {
            "openrouter" -> "openrouter.ai"
            "groq" -> "api.groq.com"
            "anthropic" -> "api.anthropic.com"
            else -> return false
        }
        return uri.host == host
    }

    const val UNAVAILABLE = "Notification analysis needs a verified tool-free inference endpoint. This agent cannot safely process notification context yet; the inbox and alert rules still work."
}
