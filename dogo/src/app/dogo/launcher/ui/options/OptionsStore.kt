/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ui.options

import android.content.Context
import app.dogo.launcher.agent.AgentOptions
import app.dogo.launcher.agent.AgentOptions.ActionType
import app.dogo.launcher.agent.backend.AgentConfig
import app.dogo.launcher.agent.backend.KeystoreSecretBox
import app.dogo.launcher.agent.backend.SecretBox

/**
 * Persists [AgentOptions].
 *
 * Uses SharedPreferences rather than the launcher's DataStore preferences
 * because these options are Dogo's own and carry no migration history. Every
 * read is defensive: a corrupt or partial file falls back to defaults rather
 * than throwing, because this is read while the launcher is starting.
 *
 * Agent settings, which include API keys, are stored as one blob sealed with a
 * Keystore key (see [KeystoreSecretBox]), never as plain text.
 */
class OptionsStore(
    context: Context,
    private val secrets: SecretBox = KeystoreSecretBox(),
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(): AgentOptions {
        val defaults = AgentOptions()
        return try {
            AgentOptions(
                agentId = prefs.getString(KEY_AGENT, null) ?: defaults.agentId,
                agentConfigs = prefs.getString(KEY_AGENT_CONFIGS, null)
                    ?.let { sealed -> runCatching { secrets.open(sealed) }.getOrNull() }
                    .let { AgentConfig.decode(it) },
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
        val sealedConfigs = runCatching { secrets.seal(AgentConfig.encode(options.agentConfigs)) }.getOrNull()
        prefs.edit()
            .putString(KEY_AGENT, options.agentId)
            .apply { if (sealedConfigs != null) putString(KEY_AGENT_CONFIGS, sealedConfigs) }
            .remove(KEY_LEGACY_RUNTIME)
            .putString(KEY_MODEL, options.modelId)
            .putString(KEY_THINKING, options.thinkingLevel.name)
            .putStringSet(KEY_ACTIONS, options.allowedActions.map { it.name }.toSet())
            .putBoolean(KEY_CONFIRM, options.requireConfirmation)
            .apply()
    }

    companion object {
        private const val NAME = "dogo_agent_options"
        private const val KEY_AGENT = "agent"
        private const val KEY_AGENT_CONFIGS = "agent_configs_sealed"

        /** Pre-agent-picker "ON_DEVICE"/"CLOUD" switch; CLOUD never had a backend. */
        private const val KEY_LEGACY_RUNTIME = "runtime"
        private const val KEY_MODEL = "model"
        private const val KEY_THINKING = "thinking"
        private const val KEY_ACTIONS = "actions"
        private const val KEY_CONFIRM = "require_confirmation"
    }
}
