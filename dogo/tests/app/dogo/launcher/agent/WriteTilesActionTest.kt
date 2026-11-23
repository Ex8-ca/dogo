/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.agent

import app.dogo.launcher.tiles.TileParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WriteTilesActionTest {
    private val file = "page = 1, grid 4x6, widget:com.example.app/com.example.ClockWidget 4x2+0+1\ngeneral { gaps = 8 }"

    @Test
    fun `a write_tiles block keeps the file text, line breaks included`() {
        val json = ActionExecutor.toJson(Action.WriteTiles(file))

        val parsed = ActionExecutor.parseAction(json)

        assertThat(parsed).isEqualTo(Action.WriteTiles(file))
    }

    @Test
    fun `the agent can send the file under either name`() {
        val viaTiles = ActionExecutor.parseAction("""{"action":"write_tiles","params":{"tiles":"page = 1, grid 4x4"}}""")

        assertThat(viaTiles).isEqualTo(Action.WriteTiles("page = 1, grid 4x4"))
    }

    @Test
    fun `an empty or missing file is not an action`() {
        assertThat(ActionExecutor.parseAction("""{"action":"write_tiles","params":{}}""")).isEqualTo(Action.Unknown)
        assertThat(ActionExecutor.parseAction("""{"action":"write_tiles","params":{"file":"   "}}""")).isEqualTo(Action.Unknown)
        assertThat(ActionExecutor.parseAction("""{"action":"write_tiles"}""")).isEqualTo(Action.Unknown)
    }

    @Test
    fun `a huge file is cut so it cannot flood the approval card`() {
        val huge = "x".repeat(TileParser.MAX_CHARS * 5)

        val parsed = ActionExecutor.parseAction(ActionExecutor.toJson(Action.WriteTiles(huge))) as Action.WriteTiles

        assertThat(parsed.text.length).isAtMost(TileParser.MAX_CHARS + 1)
    }

    @Test
    fun `it has its own switch and is refused when that switch is off`() {
        val action = Action.WriteTiles(file)
        val off = AgentOptions(allowedActions = AgentOptions.ActionType.entries.toSet() - AgentOptions.ActionType.WRITE_TILES)
        val on = AgentOptions(allowedActions = setOf(AgentOptions.ActionType.WRITE_TILES))

        assertThat(AgentOptions.ActionType.of(action)).isEqualTo(AgentOptions.ActionType.WRITE_TILES)
        assertThat(off.isAllowed(action)).isFalse()
        assertThat(on.isAllowed(action)).isTrue()
    }

    @Test
    fun `it asks for confirmation unless the user turned that off`() {
        val action = Action.WriteTiles(file)

        assertThat(AgentOptions(requireConfirmation = true).needsConfirmation(action)).isTrue()
        assertThat(AgentOptions(requireConfirmation = false).needsConfirmation(action)).isFalse()
    }

    @Test
    fun `an action that arrives inside prose is still found`() {
        val reply = "Here is a layout.\n" + ActionExecutor.toJson(Action.WriteTiles(file)) + "\nTell me if you want it changed."

        assertThat(ActionExecutor.parseActions(reply)).containsExactly(Action.WriteTiles(file))
    }
}
