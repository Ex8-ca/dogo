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
            "set_animation_scale",
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

    private fun paramsFor(action: String) = when (action) {
        "set_wallpaper" -> """{"color":"#000000"}"""
        "add_to_home" -> """{"package":"com.example"}"""
        "hide_app" -> """{"package":"com.example"}"""
        "create_folder" -> """{"name":"Work","apps":[]}"""
        "set_theme" -> """{"theme":"dark"}"""
        "set_animation_scale" -> """{"scale":0.5}"""
        else -> "{}"
    }
}
