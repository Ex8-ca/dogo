package app.dogo.launcher.ui.options

import android.content.Context
import app.dogo.launcher.agent.AgentOptions
import app.dogo.launcher.agent.AgentOptions.ActionType

/**
 * Persists [AgentOptions].
 *
 * Uses SharedPreferences rather than the launcher's DataStore preferences
 * because these options are Dogo's own and carry no migration history. Every
 * read is defensive: a corrupt or partial file falls back to defaults rather
 * than throwing, because this is read while the launcher is starting.
 */
class OptionsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(): AgentOptions {
        val defaults = AgentOptions()
        return try {
            AgentOptions(
                runtime = runCatching {
                    AgentOptions.Runtime.valueOf(prefs.getString(KEY_RUNTIME, null).orEmpty())
                }.getOrDefault(defaults.runtime),
                modelId = prefs.getString(KEY_MODEL, null),
                thinkingLevel = runCatching {
                    AgentOptions.ThinkingLevel.valueOf(
                        prefs.getString(KEY_THINKING, null).orEmpty(),
                    )
                }.getOrDefault(defaults.thinkingLevel),
                allowedActions = runCatching {
                    val raw = prefs.getStringSet(KEY_ACTIONS, null)
                    if (raw == null) {
                        defaults.allowedActions
                    } else {
                        raw.mapNotNull { name ->
                            ActionType.entries.firstOrNull { it.name == name }
                        }.toSet()
                    }
                }.getOrDefault(defaults.allowedActions),
                requireConfirmation = prefs.getBoolean(
                    KEY_CONFIRM,
                    defaults.requireConfirmation,
                ),
            )
        } catch (e: Exception) {
            defaults
        }
    }

    fun save(options: AgentOptions) {
        prefs.edit()
            .putString(KEY_RUNTIME, options.runtime.name)
            .putString(KEY_MODEL, options.modelId)
            .putString(KEY_THINKING, options.thinkingLevel.name)
            .putStringSet(KEY_ACTIONS, options.allowedActions.map { it.name }.toSet())
            .putBoolean(KEY_CONFIRM, options.requireConfirmation)
            .apply()
    }

    companion object {
        private const val NAME = "dogo_agent_options"
        private const val KEY_RUNTIME = "runtime"
        private const val KEY_MODEL = "model"
        private const val KEY_THINKING = "thinking"
        private const val KEY_ACTIONS = "actions"
        private const val KEY_CONFIRM = "require_confirmation"
    }
}
