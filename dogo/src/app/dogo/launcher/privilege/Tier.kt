/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.privilege

/**
 * How much of the system Dogo can reach, from least to most.
 *
 * Dogo never asks for more than an action needs. A change that only touches the
 * launcher's own data runs at [NONE]; a change to system settings asks for
 * whichever higher level is actually available.
 */
enum class Tier(val title: String, val blurb: String) {
    NONE(
        "Launcher only",
        "Changes to Dogo itself: home screen, folders, hidden apps, Dogo's theme.",
    ),
    ADB_GRANT(
        "Granted over adb",
        "A permission given once from a computer with adb pm grant. Survives reboots.",
    ),
    SHIZUKU(
        "Shizuku",
        "Shell-level access through the Shizuku app, without rooting the phone.",
    ),
    ROOT(
        "Root",
        "Full control through a root manager such as Magisk.",
    ),
    SYSTEM(
        "System app",
        "Dogo built into the OS (a future LineageOS build). Not available yet.",
    ),
}

/**
 * A way of running a privileged shell command.
 *
 * Shizuku and root both give Dogo a shell; the agent's actions are expressed as
 * the shell commands Android already ships (`cmd uimode`, `settings`, `pm`), so
 * every route runs exactly the same, reviewable command.
 */
interface ShellRunner {
    val tier: Tier

    /** True when this route can run a command right now, without prompting. */
    suspend fun isReady(): Boolean

    /** Runs [command] (argv, no shell parsing) and returns stdout. */
    suspend fun run(command: List<String>): Result<String>
}

/** What a [ShellRunner] reports when it isn't usable, for the status screen. */
data class TierStatus(
    val tier: Tier,
    val available: Boolean,
    val detail: String,
    /** True when asking would do something right now (the "Allow" button). */
    val canRequest: Boolean = false,
    /** A web page to get the missing app from, shown as a "Get" button. */
    val getUrl: String? = null,
)
