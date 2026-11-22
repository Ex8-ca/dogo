/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.screenuse

/** How one step went. A failure ends nothing by itself; the controller decides what to do. */
sealed interface StepResult {
    data object Done : StepResult

    /** [reason] is for the user and the agent, never raw screen text. */
    data class Failed(val reason: String) : StepResult
}

/**
 * The only way the controller touches another app. The Android version sits on an accessibility
 * service; tests use a fake. Everything it returns about the screen is untrusted.
 */
interface ScreenDriver {
    /** True when the accessibility service is switched on and connected. */
    val isReady: Boolean

    /** True while the device is locked or the screen is off. */
    val isLocked: Boolean

    /** Brings [packageName] to the front. False if it can't be started. */
    suspend fun open(packageName: String): Boolean

    /** What is on screen now, or null when the service is off or nothing is readable at all. */
    suspend fun snapshot(): ScreenSnapshot?

    /**
     * Carries out [step], which was proposed against [seen]. Re-reads the screen first and fails,
     * rather than guesses, if the element it points at has moved or changed since [seen].
     */
    suspend fun perform(step: ScreenStep, seen: ScreenSnapshot): StepResult
}
