/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.agent.backend

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TermuxCliTest {

    @Test
    fun `every known CLI runs non-interactively and has an install hint`() {
        TermuxCli.all.forEach { cli ->
            assertThat(cli.argv).isNotEmpty()
            assertThat(cli.argv.first()).doesNotContain("/") // resolved via Termux's PATH
            assertThat(cli.installHint).isNotEmpty()
        }
        assertThat(TermuxCli.all.map { it.id }).containsNoDuplicates()
        assertThat(TermuxCli.CLAUDE.argv).contains("-p")
    }

    @Test
    fun `the Termux preset picks a CLI by id and validates it`() {
        val ok = AgentCatalog.TERMUX.resolve(AgentConfig(model = "opencode"))
        assertThat(ok.problems()).isEmpty()
        assertThat(AgentCatalog.TERMUX.resolve(null).model).isEqualTo("claude")
        val bad = AgentCatalog.TERMUX.resolve(AgentConfig(model = "rm"))
        assertThat(bad.problems()).containsExactly(AgentProblem.CLI_UNKNOWN)
        // No URL or key is needed, unlike the HTTP agents.
        assertThat(ok.problems()).doesNotContain(AgentProblem.URL_MISSING)
    }

    @Test
    fun `prompt keeps the whole conversation and tells the CLI not to use tools`() {
        val prompt = TermuxCliBackend.renderPrompt(
            "SYSTEM",
            listOf(
                ChatTurn(ChatTurn.Role.USER, "hi"),
                ChatTurn(ChatTurn.Role.ASSISTANT, "hello"),
                ChatTurn(ChatTurn.Role.USER, "make it dark"),
            ),
        )
        assertThat(prompt).startsWith("SYSTEM")
        assertThat(prompt).contains("Do not run tools or edit files")
        assertThat(prompt).contains("User: hi\nAssistant: hello\nUser: make it dark\n")
        assertThat(prompt.trimEnd()).endsWith("Assistant:")
    }

    @Test
    fun `http factory does not build a Termux backend`() {
        // Termux needs a Context; only AgentBackends.create may build it.
        assertThat(AgentBackends.remote(AgentCatalog.TERMUX.resolve(null))).isNull()
    }
}
