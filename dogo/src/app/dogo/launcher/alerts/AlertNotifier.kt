/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.alerts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.ui.preferences.navigation.DogoSetup

internal object AlertNotifier {
    private const val NOTIFY = "dogo_alert_notify_v1"
    private const val INTERRUPT = "dogo_alert_interrupt_v1"
    private const val TAG_PREFIX = "dogo_assistant:"

    fun post(context: Context, candidate: AlertCandidate, score: AlertScore) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(NOTIFY, "Dogo alerts", NotificationManager.IMPORTANCE_DEFAULT).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE },
                NotificationChannel(INTERRUPT, "Dogo urgent alerts", NotificationManager.IMPORTANCE_HIGH).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE },
            ),
        )
        val open = PreferenceActivity.createIntent(context, DogoSetup)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 731, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = Notification.Builder(context, NOTIFY)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Dogo alert")
            .setContentText("Open Dogo to view this notification").build()
        val notification = Notification.Builder(context, if (score == AlertScore.INTERRUPT) INTERRUPT else NOTIFY)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(candidate.title.ifBlank { candidate.appLabel }.take(256))
            .setContentText(candidate.text.take(256))
            .setSubText(candidate.appLabel)
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_MESSAGE).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public).build()
        // A stable tag plus fixed id is collision-free across different originating keys.
        manager.notify(TAG_PREFIX + candidate.key, 1, notification)
    }

    fun cancel(context: Context, key: String) {
        context.getSystemService(NotificationManager::class.java).cancel(TAG_PREFIX + key, 1)
    }

    fun cancelAll(context: Context) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.activeNotifications.filter { it.tag?.startsWith(TAG_PREFIX) == true }.forEach { manager.cancel(it.tag, it.id) }
        }
    }
}
