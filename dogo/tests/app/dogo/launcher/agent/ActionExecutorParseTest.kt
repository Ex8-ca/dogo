/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.agent

import app.dogo.launcher.agent.Action
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Parses the JSON action blocks the on-device model emits. Parsing must never
 * throw — malformed model output has to degrade to [Action.Unknown].
 */
class ActionExecutorParseTest {

    @Test
    fun `parses set_theme`() {
        val action = ActionExecutor.parseAction("""{"action":"set_theme","params":{"theme":"dark"}}""")

        assertThat(action).isInstanceOf(Action.SetTheme::class.java)
        assertThat((action as Action.SetTheme).theme).isEqualTo("dark")
    }

    @Test
    fun `parses set_wallpaper`() {
        val action =
            ActionExecutor.parseAction("""{"action":"set_wallpaper","params":{"color":"#1E1E1E"}}""")

        assertThat(action).isInstanceOf(Action.SetWallpaper::class.java)
        assertThat((action as Action.SetWallpaper).color).isEqualTo("#1E1E1E")
    }

    @Test
    fun `parses add_to_home`() {
        val action = ActionExecutor.parseAction(
            """{"action":"add_to_home","params":{"package":"org.telegram.messenger"}}""",
        )

        assertThat(action).isInstanceOf(Action.AddToHome::class.java)
        assertThat((action as Action.AddToHome).pkg).isEqualTo("org.telegram.messenger")
    }

    @Test
    fun `parses hide_app`() {
        val action =
            ActionExecutor.parseAction("""{"action":"hide_app","params":{"package":"com.example.game"}}""")

        assertThat(action).isInstanceOf(Action.HideApp::class.java)
        assertThat((action as Action.HideApp).pkg).isEqualTo("com.example.game")
    }

    @Test
    fun `parses create_folder with app list`() {
        val action = ActionExecutor.parseAction(
            """{"action":"create_folder","params":{"name":"Work","apps":["com.slack","com.microsoft.office"]}}""",
        )

        assertThat(action).isInstanceOf(Action.CreateFolder::class.java)
        val folder = action as Action.CreateFolder
        assertThat(folder.name).isEqualTo("Work")
        assertThat(folder.apps).containsExactly("com.slack", "com.microsoft.office").inOrder()
    }

    @Test
    fun `parses create_folder with an empty app list`() {
        val action =
            ActionExecutor.parseAction("""{"action":"create_folder","params":{"name":"Later"}}""")

        assertThat(action).isInstanceOf(Action.CreateFolder::class.java)
        assertThat((action as Action.CreateFolder).apps).isEmpty()
    }

    @Test
    fun `parses set_animation_scale`() {
        val action =
            ActionExecutor.parseAction("""{"action":"set_animation_scale","params":{"scale":0.5}}""")

        assertThat(action).isInstanceOf(Action.SetAnimationScale::class.java)
        assertThat((action as Action.SetAnimationScale).scale).isWithin(1e-6f).of(0.5f)
    }

    @Test
    fun `unknown action name yields Unknown`() {
        val action = ActionExecutor.parseAction("""{"action":"nonexistent"}""")

        assertThat(action).isEqualTo(Action.Unknown)
    }

    @Test
    fun `missing params object yields Unknown`() {
        val action = ActionExecutor.parseAction("""{"action":"set_theme"}""")

        assertThat(action).isEqualTo(Action.Unknown)
    }

    @Test
    fun `garbage input yields Unknown instead of throwing`() {
        val inputs = listOf("", "not json at all", "{", "[]", "null", "42")

        inputs.forEach { input ->
            assertThat(ActionExecutor.parseAction(input)).isEqualTo(Action.Unknown)
        }
    }

    @Test
    fun `text around the JSON block still parses`() {
        // Models like to wrap JSON in prose or fenced code blocks.
        val action = ActionExecutor.parseAction(
            """
            Sure! Here you go:
            ```json
            {"action":"set_theme","params":{"theme":"light"}}
            ```
            Let me know if you want anything else.
            """.trimIndent(),
        )

        assertThat(action).isInstanceOf(Action.SetTheme::class.java)
        assertThat((action as Action.SetTheme).theme).isEqualTo("light")
    }

    @Test
    fun `extracts the first action when several are present`() {
        val text = """
            {"action":"set_theme","params":{"theme":"dark"}}
            {"action":"hide_app","params":{"package":"com.example.game"}}
        """.trimIndent()

        assertThat(ActionExecutor.extractFirstJsonAction(text))
            .isEqualTo("""{"action":"set_theme","params":{"theme":"dark"}}""")
    }

    @Test
    fun `extract returns null when there is no action`() {
        assertThat(ActionExecutor.extractFirstJsonAction("I need more information.")).isNull()
    }

    @Test
    fun `serialises an action back to the same json shape`() {
        val action = Action.SetTheme("dark")

        assertThat(ActionExecutor.toJson(action))
            .isEqualTo("""{"action":"set_theme","params":{"theme":"dark"}}""")
    }

    @Test
    fun `round trips through parse`() {
        val original = Action.CreateFolder("Work", listOf("com.slack", "com.microsoft.office"))

        val roundTripped = ActionExecutor.parseAction(ActionExecutor.toJson(original))

        assertThat(roundTripped).isEqualTo(original)
    }

    @Test
    fun `parseActions finds every action in a reply`() {
        val reply = """
            Sure. I'll make it dark and tidy up.
            {"action": "set_theme", "params": {"theme": "dark"}}
            {"action": "create_folder", "params": {"name": "Work", "apps": ["com.slack"]}}
            and {broken prose
            {"action": "nope", "params": {}}
            {"action": "set_animation_scale", "params": {"scale": 0.5}}
        """.trimIndent()

        val actions = ActionExecutor.parseActions(reply)

        assertThat(actions).containsExactly(
            Action.SetTheme("dark"),
            Action.CreateFolder("Work", listOf("com.slack")),
            Action.SetAnimationScale(0.5f),
        ).inOrder()
    }

    @Test
    fun `parseActions returns nothing for plain chat`() {
        assertThat(ActionExecutor.parseActions("What apps do you use most?")).isEmpty()
        assertThat(ActionExecutor.parseActions("")).isEmpty()
    }
}
