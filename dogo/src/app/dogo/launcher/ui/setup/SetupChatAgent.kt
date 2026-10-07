package app.dogo.launcher.ui.setup

/**
 * Prompt construction for the conversational setup assistant.
 *
 * Kept free of Android and inference-library types so the wording can be unit
 * tested and iterated on without a device.
 */
object SetupChatAgent {

    /**
     * Instructions for the on-device model.
     *
     * The action names listed here must stay in sync with
     * [app.dogo.launcher.agent.ActionExecutor]; [SetupChatAgentTest] enforces it.
     */
    val systemPrompt = """
        You are Dogo Setup, an assistant that configures an Android phone for the
        person you are talking to.

        Your job is to have a short conversation to find out how the user wants
        their phone set up, then propose concrete changes. Ask clarifying questions
        before proposing anything, and keep replies brief and conversational.

        When you are ready to propose changes, emit one JSON action per line:

        {"action": "set_theme", "params": {"theme": "dark"}}
        {"action": "set_wallpaper", "params": {"color": "#1E1E1E"}}
        {"action": "add_to_home", "params": {"package": "com.example.app"}}
        {"action": "hide_app", "params": {"package": "com.example.app"}}
        {"action": "create_folder", "params": {"name": "Work", "apps": ["com.example.app"]}}
        {"action": "set_animation_scale", "params": {"scale": 0.5}}

        Use real Android package names when you name an app. Explain each action in
        one short sentence. Nothing is applied until the user approves it.
    """.trimIndent()

    /**
     * Builds a full prompt for [userMessage].
     *
     * @param history prior turns as `role` to `message`, where role is "user" or
     *   "assistant".
     * @param screenText visible text from the foreground app, when available.
     */
    fun buildPrompt(
        userMessage: String,
        history: List<Pair<String, String>>,
        screenText: String? = null,
    ): String = buildString {
        append(systemPrompt)
        append("\n\n")

        if (screenText != null) {
            append("Currently visible on screen:\n")
            append(screenText.ifBlank { "(nothing readable)" })
            append("\n\n")
        } else {
            append("Currently visible on screen: (unavailable)\n\n")
        }

        if (history.isNotEmpty()) {
            append("Conversation so far:\n")
            history.forEach { (role, message) ->
                append(if (role == "user") "User" else "Assistant")
                append(": ")
                append(message)
                append("\n")
            }
            append("\n")
        }

        append("User: ")
        append(userMessage)
        append("\nAssistant:")
    }
}
