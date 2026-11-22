/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.agent.backend

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ToolFreePolicyTest {
    @Test
    fun `agent servers and disguised inference hosts fail closed`() {
        assertThat(ToolFreePolicy.supports("hermes", "https://api.groq.com/openai/v1")).isFalse()
        assertThat(ToolFreePolicy.supports("custom", "https://api.groq.com/openai/v1")).isFalse()
        assertThat(ToolFreePolicy.supports("groq", "https://api.groq.com.evil.org/v1")).isFalse()
        assertThat(ToolFreePolicy.supports("groq", "http://api.groq.com/v1")).isFalse()
        assertThat(ToolFreePolicy.supports("groq", "https://user:pass@api.groq.com/v1")).isFalse()
        assertThat(ToolFreePolicy.supports("groq", "https://api.groq.com:8443/v1")).isFalse()
    }

    @Test
    fun `reviewed direct inference hosts are accepted without changing provider`() {
        assertThat(ToolFreePolicy.supports("groq", "https://api.groq.com/openai/v1")).isTrue()
        assertThat(ToolFreePolicy.supports("openrouter", "https://openrouter.ai/api/v1")).isTrue()
        assertThat(ToolFreePolicy.supports("anthropic", "https://api.anthropic.com/v1")).isTrue()
        assertThat(ToolFreePolicy.supports("anthropic", "https://openrouter.ai/v1")).isFalse()
    }
}
