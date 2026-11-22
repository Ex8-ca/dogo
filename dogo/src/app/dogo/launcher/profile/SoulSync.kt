/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.profile

import android.content.Context
import app.dogo.launcher.agent.backend.AgentCatalog
import app.dogo.launcher.agent.backend.resolve
import app.dogo.launcher.ui.options.OptionsStore
import app.dogo.launcher.voice.HermesHandoff

/**
 * Pulls the user's soul from their Hermes agent and keeps a copy on the phone. The copy replaces
 * Dogo's built-in soul; the user's own additions are kept apart and never touched.
 */
object SoulSync {
    sealed interface Result {
        data class Synced(val chars: Int, val name: String) : Result

        /** Hermes answered but has no soul to give (or it was too long). Nothing was changed. */
        data class Nothing(val why: String) : Result

        data class Failed(val why: String) : Result
    }

    private const val PREFS = "dogo_soul_sync"
    private const val KEY_LAST_TRY = "last_try"
    private const val RETRY_MS = 24L * 60 * 60 * 1000

    /** True when Hermes is set up and the built-in soul is still in use and we haven't tried today. */
    fun shouldAutoSync(context: Context, now: Long): Boolean {
        if (SoulStore.of(context).read().source != SoulStore.Source.BUILT_IN) return false
        if (!hermesReady(context)) return false
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_TRY, 0L)
        return now - last > RETRY_MS
    }

    fun hermesReady(context: Context): Boolean = hermes(context) != null

    private fun hermes(context: Context) = OptionsStore(context).load().agentConfigs[AgentCatalog.HERMES.id]
        ?.let { AgentCatalog.HERMES.resolve(it) }
        ?.takeIf { it.problems().isEmpty() && !it.apiKey.isNullOrBlank() }

    suspend fun sync(context: Context, now: Long = System.currentTimeMillis()): Result {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_LAST_TRY, now).apply()
        val hermes = hermes(context) ?: return Result.Failed("Set up Hermes Agent first (Dogo Options > Agent).")
        val reply = HermesHandoff(hermes.baseUrl, hermes.apiKey!!, timeoutMs = 90_000).ask(ProfileText.IMPORT_REQUEST)
        val found = ProfileText.parseImport(reply)
            ?: return Result.Failed("Hermes didn't answer in a form Dogo can use. Nothing was changed.")
        val store = ProfileStore(context)
        if (found.name.isNotEmpty()) store.save(store.load().copy(name = found.name))
        val soul = found.soul
        return when {
            soul != null -> {
                SoulStore.of(context).saveFromHermes(soul, now)
                Result.Synced(soul.length, found.name)
            }

            found.tooLong -> Result.Nothing("Hermes's soul is longer than ${ProfileText.MAX_SOUL} characters, so Dogo kept its own.")

            else -> Result.Nothing("Hermes has no SOUL.md to share, so Dogo kept its own.")
        }
    }
}
