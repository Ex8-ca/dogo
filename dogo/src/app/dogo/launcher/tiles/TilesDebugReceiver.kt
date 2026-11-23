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
import com.android.launcher3.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
                val outcome = ActionApplier(app).apply(Action.WriteTiles(text))
                Log.i(TAG, "ok=${outcome.ok} message=${outcome.message}")
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
