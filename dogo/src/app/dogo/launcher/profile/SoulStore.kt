/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.profile

import java.io.File
import org.json.JSONObject

/**
 * The soul kept on the phone: Dogo's built-in one until a Hermes soul is synced, which replaces
 * it. Held in app storage excluded from backup. The user's own additions are separate (see
 * [AssistantProfile.style]) so a sync never erases them.
 */
class SoulStore(private val dir: File) {
    enum class Source { BUILT_IN, HERMES }

    data class Soul(val text: String, val source: Source, val syncedAt: Long)

    private val file get() = File(dir, "soul.json")

    fun read(): Soul {
        val saved = runCatching {
            val o = JSONObject(file.readText())
            val text = ProfileText.cleanMultiline(o.optString("text"), ProfileText.MAX_SOUL)
            if (text.isEmpty()) null else Soul(text, Source.HERMES, o.optLong("synced_at"))
        }.getOrNull()
        return saved ?: Soul(DefaultSoul.TEXT, Source.BUILT_IN, 0L)
    }

    fun saveFromHermes(text: String, now: Long) {
        val clean = ProfileText.cleanMultiline(text, ProfileText.MAX_SOUL)
        require(clean.isNotEmpty()) { "empty soul" }
        dir.mkdirs()
        val tmp = File(dir, "soul.json.tmp")
        tmp.writeText(JSONObject().put("text", clean).put("synced_at", now).put("source", "hermes").toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    /** Back to Dogo's built-in soul. */
    fun reset() {
        file.delete()
    }

    companion object {
        fun of(context: android.content.Context) = SoulStore(File(context.applicationContext.noBackupFilesDir, "soul"))
    }
}
