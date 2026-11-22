/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ui.setup

/**
 * Prompt construction for the conversational setup assistant.
 *
 * Kept free of Android and inference-library types so the wording can be unit
 * tested and iterated on without a device.
 */
object SetupChatAgent {

    /** An app the agent may refer to. */
    data class InstalledApp(val label: String, val packageName: String)

    /** An action from an enabled plugin, e.g. `io.github.you.timer:start_timer`. */
    data class PluginAction(val qualifiedName: String, val description: String, val params: Map<String, String>)

    /** What the phone can actually do right now, so the agent doesn't over-promise. */
    data class PhoneContext(
        val apps: List<InstalledApp> = emptyList(),
        val pluginActions: List<PluginAction> = emptyList(),
        val systemDarkModeAvailable: Boolean = false,
        val animationScaleAvailable: Boolean = false,
    )

    /** Keeps the app list from crowding out the conversation on small models. */
    const val MAX_APPS = 150

    /**
     * Instructions for the agent.
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
        {"action": "set_system_dark_mode", "params": {"enabled": true}}
        {"action": "set_wallpaper", "params": {"color": "#1E1E1E"}}
        {"action": "add_to_home", "params": {"package": "com.example.app"}}
        {"action": "hide_app", "params": {"package": "com.example.app"}}
        {"action": "create_folder", "params": {"name": "Work", "apps": ["com.example.app"]}}
        {"action": "set_animation_scale", "params": {"scale": 0.5}}
        {"action": "arrange_ring", "params": {"groups": [{"name": "Talk", "apps": ["com.example.app"]}], "style": "glow"}}

        set_theme changes only the Dogo launcher (light, dark or system).
        arrange_ring sets up the Dogo Ring, the radial menu that opens when the
        user touches and holds the home screen. Use 3 to 7 short group names
        (one word if possible); "style" is optional: glow, radar or minimal.
        set_system_dark_mode changes the whole phone's dark mode.
        Only use package names from the installed apps list below. If an app the
        user mentions isn't installed, say so instead of guessing a package name.
        Explain each action in one short sentence. Nothing is applied until the
        user approves it.
    """.trimIndent()

    /** The system prompt plus facts about this phone. */
    fun systemPromptFor(phone: PhoneContext): String = buildString {
        append(systemPrompt)
        append("\n\n")
        append(phoneSection(phone))
    }

    internal fun phoneSection(phone: PhoneContext): String = buildString {
        append("About this phone:\n")
        append("- Changing the phone's dark mode is ")
        append(if (phone.systemDarkModeAvailable) "available.\n" else "NOT available yet; offer set_theme for Dogo instead.\n")
        append("- Changing animation speed is ")
        append(if (phone.animationScaleAvailable) "available.\n" else "NOT available yet.\n")
        if (phone.pluginActions.isNotEmpty()) {
            append("\nPlugin actions you can also propose (same JSON shape, use the full name):\n")
            phone.pluginActions.forEach { a ->
                append("- ").append(a.qualifiedName)
                if (a.params.isNotEmpty()) {
                    append(" params {").append(a.params.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" }).append("}")
                }
                if (a.description.isNotBlank()) append(": ").append(a.description)
                append('\n')
            }
        }
        if (phone.apps.isEmpty()) {
            append("\nInstalled apps: (unknown)\n")
        } else {
            val shown = phone.apps.sortedBy { it.label.lowercase() }.take(MAX_APPS)
            append("\nInstalled apps (label = package):\n")
            shown.forEach { append("- ").append(it.label).append(" = ").append(it.packageName).append('\n') }
            if (phone.apps.size > shown.size) {
                append("(and ${phone.apps.size - shown.size} more not listed)\n")
            }
        }
    }

    /**
     * Builds a full single-string prompt for small on-device models.
     *
     * @param history prior turns as `role` to `message`, where role is "user" or
     *   "assistant".
     * @param screenText visible text from the foreground app, when available.
     */
    fun buildPrompt(
        userMessage: String,
        history: List<Pair<String, String>>,
        screenText: String? = null,
        phone: PhoneContext? = null,
    ): String = buildString {
        append(phone?.let { systemPromptFor(it) } ?: systemPrompt)
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
