/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ui.setup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnswerSourcesTest {
    @Test
    fun `valid final URL punctuation is preserved`() {
        assertThat(AnswerSources.extract("https://example.org/important! https://example.org/a; https://example.org/a?").map { it.url })
            .containsExactly("https://example.org/important!", "https://example.org/a;", "https://example.org/a?").inOrder()
    }

    @Test
    fun `markdown and plain links open the exact cited address`() {
        val sources = AnswerSources.extract("Official [downloads](https://download.lineageos.org/). Also https://www.example.org/news, and https://example.org/a_(b).")
        assertThat(sources.map { it.url }).containsExactly(
            "https://download.lineageos.org/",
            "https://www.example.org/news",
            "https://example.org/a_(b)",
        ).inOrder()
        assertThat(sources[1].host).isEqualTo("example.org")
    }

    @Test
    fun `no executable schemes credentials or duplicate buttons`() {
        val sources = AnswerSources.extract("javascript:alert(1) file:///data/x https://user:secret@example.org/private https://example.org https://example.org")
        assertThat(sources.map { it.url }).containsExactly("https://example.org")
        assertThat(AnswerSources.extract("No links here")).isEmpty()
    }
}
