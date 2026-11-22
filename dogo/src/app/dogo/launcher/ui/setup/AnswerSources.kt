/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ui.setup

import java.net.URI

/** Links mentioned by an answer, not proof that the agent actually read them. */
data class AnswerSource(val url: String, val host: String)

object AnswerSources {
    private val links = Regex("https?://[^\\s<>\"`]+", RegexOption.IGNORE_CASE)

    fun extract(text: String): List<AnswerSource> = links.findAll(text).mapNotNull { match ->
        var url = match.value.trimEnd('.', ',', '\'', ']', '}', '。')
        // Markdown closing brackets aren't part of the URL. Balanced parentheses are.
        while (url.endsWith(')') && url.count { it == ')' } > url.count { it == '(' }) url = url.dropLast(1)
        runCatching {
            val uri = URI(url)
            val host = uri.host ?: return@mapNotNull null
            if (uri.userInfo != null || host.isBlank()) return@mapNotNull null
            AnswerSource(url, host.removePrefix("www."))
        }.getOrNull()
    }.distinctBy { it.url }.take(8).toList()
}
