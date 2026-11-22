/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ui.setup

import app.dogo.launcher.agent.ActionExecutor
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SetupChatAgentTest {

    @Test
    fun `system prompt tells the model what it is for`() {
        assertThat(SetupChatAgent.systemPrompt).contains("Dogo")
        assertThat(SetupChatAgent.systemPrompt).contains("Android phone")
    }

    @Test
    fun `system prompt asks clarifying questions before acting`() {
        assertThat(SetupChatAgent.systemPrompt).contains("clarifying")
    }

    @Test
    fun `system prompt documents every action the executor understands`() {
        // If these ever drift apart the model will emit actions that parse to
        // Unknown and the agent will silently do nothing.
        val documented = listOf(
            "set_wallpaper",
            "add_to_home",
            "hide_app",
            "create_folder",
            "set_theme",
            "set_system_dark_mode",
            "set_animation_scale",
            "arrange_ring",
            "add_widget",
            "style_home",
            "set_dock",
        )

        documented.forEach { action ->
            assertThat(SetupChatAgent.systemPrompt).contains(action)
            // Prove the action name is one the executor actually handles.
            val json = """{"action":"$action","params":${paramsFor(action)}}"""
            assertThat(ActionExecutor.parseAction(json)).isNotEqualTo(ActionExecutor.parseAction("{}"))
        }
    }

    @Test
    fun `prompt includes the current conversation`() {
        val prompt = SetupChatAgent.buildPrompt(
            userMessage = "hide the games",
            history = listOf("user" to "hi", "assistant" to "hello"),
        )

        assertThat(prompt).contains("User: hi")
        assertThat(prompt).contains("Assistant: hello")
        assertThat(prompt).contains("User: hide the games")
    }

    @Test
    fun `prompt ends with the assistant turn so the model continues it`() {
        val prompt = SetupChatAgent.buildPrompt("hi", emptyList())

        assertThat(prompt.trim()).endsWith("Assistant:")
    }

    @Test
    fun `first user turn is not labelled as history`() {
        val prompt = SetupChatAgent.buildPrompt("hide the games", emptyList())

        // Avoids "User: \nUser: hide the games"
        assertThat(prompt).doesNotContain("User: \n")
    }

    @Test
    fun `screen context is included when present`() {
        val prompt = SetupChatAgent.buildPrompt(
            userMessage = "what is this screen?",
            history = emptyList(),
            screenText = "Settings > Network",
        )

        assertThat(prompt).contains("Network")
    }

    @Test
    fun `absent screen context says so instead of being blank`() {
        val prompt = SetupChatAgent.buildPrompt("hi", emptyList(), screenText = null)

        assertThat(prompt).contains("(unavailable)")
    }

    @Test
    fun `phone section lists installed apps sorted with their packages`() {
        val section = SetupChatAgent.phoneSection(
            SetupChatAgent.PhoneContext(
                apps = listOf(
                    SetupChatAgent.InstalledApp("Termux", "com.termux"),
                    SetupChatAgent.InstalledApp("Calculator", "com.android.calculator2"),
                ),
            ),
        )
        assertThat(section).contains("- Calculator = com.android.calculator2")
        assertThat(section.indexOf("Calculator")).isLessThan(section.indexOf("Termux"))
    }

    @Test
    fun `the prompt tells the agent not to guess package names`() {
        assertThat(SetupChatAgent.systemPrompt).contains("Only use package names from the installed apps list")
        assertThat(SetupChatAgent.systemPrompt).contains("set_theme changes only the Dogo launcher")
    }

    @Test
    fun `unavailable system changes are flagged so the agent doesn't promise them`() {
        val off = SetupChatAgent.phoneSection(SetupChatAgent.PhoneContext())
        assertThat(off).contains("dark mode is NOT available")
        assertThat(off).contains("animation speed is NOT available")
        val on = SetupChatAgent.phoneSection(
            SetupChatAgent.PhoneContext(systemDarkModeAvailable = true, animationScaleAvailable = true),
        )
        assertThat(on).doesNotContain("NOT available")
    }

    @Test
    fun `long app lists are capped`() {
        val apps = (1..(SetupChatAgent.MAX_APPS + 7)).map { SetupChatAgent.InstalledApp("App $it", "p.$it") }
        val section = SetupChatAgent.phoneSection(SetupChatAgent.PhoneContext(apps = apps))
        assertThat(section).contains("(and 7 more not listed)")
    }

    @Test
    fun `on-device prompt includes the phone section when given`() {
        val prompt = SetupChatAgent.buildPrompt(
            "hi",
            emptyList(),
            phone = SetupChatAgent.PhoneContext(apps = listOf(SetupChatAgent.InstalledApp("Files", "com.android.documentsui"))),
        )
        assertThat(prompt).contains("com.android.documentsui")
    }

    @Test
    fun `phone section lists widgets, the current layout and icon packs`() {
        val text = SetupChatAgent.phoneSection(
            SetupChatAgent.PhoneContext(
                widgets = listOf("com.android.deskclock/com.android.alarmclock.DigitalAppWidgetProvider = Clock: Digital clock (4x2)"),
                home = listOf("Grid: 4 columns x 6 rows; dock has 4 slots."),
                iconPacks = listOf(SetupChatAgent.InstalledApp("Arcticons", "com.donnnno.arcticons")),
            ),
        )
        assertThat(text).contains("DigitalAppWidgetProvider = Clock: Digital clock (4x2)")
        assertThat(text).contains("Grid: 4 columns x 6 rows")
        assertThat(text).contains("Arcticons = com.donnnno.arcticons")
    }

    @Test
    fun `prompt explains copying a phone from a photo`() {
        assertThat(SetupChatAgent.systemPrompt).contains("photo of another phone")
        assertThat(SetupChatAgent.systemPrompt).contains("squircle")
    }

    private fun paramsFor(action: String) = when (action) {
        "set_wallpaper" -> """{"color":"#000000"}"""
        "add_to_home" -> """{"package":"com.example"}"""
        "hide_app" -> """{"package":"com.example"}"""
        "create_folder" -> """{"name":"Work","apps":[]}"""
        "set_theme" -> """{"theme":"dark"}"""
        "set_animation_scale" -> """{"scale":0.5}"""
        "set_system_dark_mode" -> """{"enabled":true}"""
        "arrange_ring" -> """{"style":"glow"}"""
        "add_widget" -> """{"widget":"com.example/.W"}"""
        "style_home" -> """{"labels":true}"""
        "set_dock" -> """{"apps":["com.example"]}"""
        else -> "{}"
    }
}
