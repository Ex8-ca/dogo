/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.tiles

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.dogo.launcher.agent.Action
import app.dogo.launcher.privilege.ActionApplier
import app.dogo.launcher.ui.setup.Assistant
import app.dogo.launcher.ui.setup.SetupChatViewModel
import com.android.launcher3.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Debug builds only, and only callable from adb (the manifest requires DUMP, which apps cannot
 * hold). Runs a tile file through the real applier, the same path an approved `write_tiles`
 * action takes, so tiles can be tried on a phone before an agent writes any.
 *
 * `adb shell am broadcast -n <app id>/app.dogo.launcher.tiles.TilesDebugReceiver --es file 'page = 1, ...|general { gaps = 8 }'`
 *
 * `|` stands for a line break. `--ez undo true` re-applies the file before the current one.
 */
class TilesDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val app = context.applicationContext
        val text = if (intent.getBooleanExtra("undo", false)) {
            TileStore(app).load().previousFile
        } else {
            intent.getStringExtra("file").orEmpty().replace('|', '\n')
        }
        if (text.isBlank()) {
            Log.i(TAG, "nothing to apply")
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val applier = ActionApplier(app)
                if (intent.getBooleanExtra("card", false)) {
                    withContext(Dispatchers.Main) {
                        Assistant.init(app)
                        val proposals = Assistant.proposalsFor(listOf(Action.WriteTiles(text)))
                        Assistant.add(SetupChatViewModel.Message("Tile preview test (adb). Nothing changes until Apply.", fromUser = false, proposals = proposals))
                    }
                    Log.i(TAG, "approval card added; no apply")
                } else if (intent.getBooleanExtra("preview", false)) {
                    Log.i(TAG, "preview=${applier.previewTiles(text).joinToString(" | ")}")
                } else {
                    val outcome = applier.apply(Action.WriteTiles(text))
                    Log.i(TAG, "ok=${outcome.ok} message=${outcome.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "failed: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DogoTiles"
    }
}
