/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.alerts

/**
 * One notification, reduced to bounded plain fields. Memory-only by default;
 * serializing [title] and [text] requires the separate seven-day history opt-in.
 * Never log these fields: they are other people's messages.
 */
data class AlertCandidate(
    /** The system's key for the notification; stable while the notification is edited. */
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val sender: String?,
    val conversation: String?,
    val isGroupConversation: Boolean,
    /** The notification's category string, e.g. "msg" or "transport". */
    val category: String?,
    val postedAt: Long,
    val ongoing: Boolean = false,
    val groupSummary: Boolean = false,
    val media: Boolean = false,
)
