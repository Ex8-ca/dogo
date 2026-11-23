/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.tiles

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * What Dogo remembers about tiles: the ids of the widgets it placed for them, the file that made
 * them, and the file before that (for Undo). Kept out of Android backup, because widget ids only
 * mean something on this phone.
 *
 * Only widgets listed here are ever moved, resized or removed by a tile file.
 */
class TileStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "tiles/state.json")

    data class State(val widgetIds: Set<Int>, val file: String, val previousFile: String)

    fun load(): State = try {
        val json = JSONObject(file.readText())
        val ids = json.optJSONArray("widgetIds") ?: JSONArray()
        State(
            widgetIds = (0 until ids.length()).map { ids.optInt(it, -1) }.filter { it >= 0 }.toSet(),
            file = json.optString("file"),
            previousFile = json.optString("previousFile"),
        )
    } catch (e: Exception) {
        State(emptySet(), "", "")
    }

    fun save(state: State) {
        file.parentFile?.mkdirs()
        val json = JSONObject()
            .put("widgetIds", JSONArray(state.widgetIds.sorted()))
            .put("file", state.file.take(MAX_FILE_CHARS))
            .put("previousFile", state.previousFile.take(MAX_FILE_CHARS))
        file.writeText(json.toString())
    }

    private companion object {
        const val MAX_FILE_CHARS = 8000
    }
}
