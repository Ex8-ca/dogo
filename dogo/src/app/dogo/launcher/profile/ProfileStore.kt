/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.profile

import android.content.Context

/** Keeps the profile in private app storage. It holds no secrets. */
class ProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(): AssistantProfile = AssistantProfile(
        name = ProfileText.cleanName(prefs.getString(KEY_NAME, "").orEmpty()),
        style = ProfileText.clean(prefs.getString(KEY_STYLE, "").orEmpty(), ProfileText.MAX_STYLE),
        recallEarlier = prefs.getBoolean(KEY_RECALL, true),
    )

    fun save(profile: AssistantProfile) {
        prefs.edit()
            .putString(KEY_NAME, ProfileText.cleanName(profile.name))
            .putString(KEY_STYLE, ProfileText.clean(profile.style, ProfileText.MAX_STYLE))
            .putBoolean(KEY_RECALL, profile.recallEarlier)
            .apply()
    }

    private companion object {
        const val NAME = "dogo_assistant_profile"
        const val KEY_NAME = "name"
        const val KEY_STYLE = "style"
        const val KEY_RECALL = "recall_earlier"
    }
}
