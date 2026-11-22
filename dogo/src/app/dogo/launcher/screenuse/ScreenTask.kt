/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.screenuse

/**
 * What the user approved: one app, one goal, a step limit and a time limit. Immutable.
 *
 * Out-of-range limits are clamped, not rejected: [maxSteps] to 1..[MAX_STEPS_CAP] and
 * [deadlineMs] to 1 second..[MAX_DEADLINE_MS_CAP]. The goal is the user's own words from the
 * approval card; it is never taken from screen text.
 */
class ScreenTask(
    appPackage: String,
    goal: String,
    maxSteps: Int = DEFAULT_MAX_STEPS,
    deadlineMs: Long = DEFAULT_DEADLINE_MS,
) {
    val appPackage: String = appPackage.trim()
    val goal: String = goal.trim().take(MAX_GOAL_CHARS)
    val maxSteps: Int = maxSteps.coerceIn(1, MAX_STEPS_CAP)
    val deadlineMs: Long = deadlineMs.coerceIn(MIN_DEADLINE_MS, MAX_DEADLINE_MS_CAP)

    init {
        require(this.appPackage.isNotEmpty()) { "appPackage must not be blank" }
        require(this.goal.isNotEmpty()) { "goal must not be blank" }
    }

    override fun equals(other: Any?): Boolean = other is ScreenTask &&
        appPackage == other.appPackage && goal == other.goal &&
        maxSteps == other.maxSteps && deadlineMs == other.deadlineMs

    override fun hashCode(): Int = listOf(appPackage, goal, maxSteps, deadlineMs).hashCode()

    override fun toString(): String = "ScreenTask(appPackage=$appPackage, goal=$goal, maxSteps=$maxSteps, deadlineMs=$deadlineMs)"

    companion object {
        const val DEFAULT_MAX_STEPS = 20
        const val MAX_STEPS_CAP = 40
        const val DEFAULT_DEADLINE_MS = 5 * 60 * 1000L
        const val MAX_DEADLINE_MS_CAP = 15 * 60 * 1000L
        const val MIN_DEADLINE_MS = 1000L
        const val MAX_GOAL_CHARS = 500
    }
}

/** Why a run is over, or [Running]. Every state except [Running] is terminal. */
sealed interface ScreenRunState {
    val isTerminal: Boolean get() = this !is Running

    data object Running : ScreenRunState

    /** The user pressed Stop. */
    data object Stopped : ScreenRunState

    /** The agent reported the goal done. */
    data object Finished : ScreenRunState

    /** The step budget is used up. */
    data object StepLimit : ScreenRunState

    data object Timeout : ScreenRunState

    /** The foreground app is no longer the approved one (or could not be determined). */
    data object LeftApp : ScreenRunState

    data object ScreenLocked : ScreenRunState

    data class Failed(val reason: String) : ScreenRunState
}

/**
 * Lifecycle of one approved [ScreenTask]. The clock is injected (milliseconds, monotonic) so
 * tests control time. Once the state leaves [ScreenRunState.Running] it never changes again,
 * and [recordStep] is rejected. Mutators are synchronized: Stop comes from the UI thread while
 * the loop runs elsewhere.
 */
class ScreenTaskRun(
    val task: ScreenTask,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val startedAt: Long = clock()

    @Volatile
    private var current: ScreenRunState = ScreenRunState.Running

    @Volatile
    private var steps: Int = 0

    val state: ScreenRunState get() = current
    val stepsTaken: Int get() = steps
    val isRunning: Boolean get() = current === ScreenRunState.Running

    /** Milliseconds left before [ScreenRunState.Timeout]; 0 once the deadline passed. */
    fun remainingMs(): Long = (startedAt + task.deadlineMs - clock()).coerceAtLeast(0L)

    /**
     * Counts one step about to be taken. Returns true if it may go ahead. Returns false, and the
     * state is terminal, if the run was already over, the deadline passed (Timeout), or the step
     * budget is already used (StepLimit). So at most [ScreenTask.maxSteps] steps are accepted.
     */
    @Synchronized
    fun recordStep(): Boolean {
        if (current !== ScreenRunState.Running) return false
        if (timedOut()) {
            current = ScreenRunState.Timeout
            return false
        }
        if (steps >= task.maxSteps) {
            current = ScreenRunState.StepLimit
            return false
        }
        steps++
        return true
    }

    /**
     * Checks the world after a step: screen locked ends the run first, then leaving the approved
     * package (null or blank counts as left: if we cannot tell, we stop), then the deadline.
     * Returns the resulting state.
     */
    @Synchronized
    fun observe(currentPackage: String?, locked: Boolean): ScreenRunState {
        if (current !== ScreenRunState.Running) return current
        current = when {
            locked -> ScreenRunState.ScreenLocked
            currentPackage.isNullOrBlank() || currentPackage.trim() != task.appPackage -> ScreenRunState.LeftApp
            timedOut() -> ScreenRunState.Timeout
            else -> ScreenRunState.Running
        }
        return current
    }

    /** The user's Stop. No effect on a run that is already over. */
    @Synchronized
    fun stop(): ScreenRunState = end(ScreenRunState.Stopped)

    /** The agent says the goal is done. No effect on a run that is already over. */
    @Synchronized
    fun finish(): ScreenRunState = end(ScreenRunState.Finished)

    /** Something broke (service gone, protocol error). No effect on a run that is already over. */
    @Synchronized
    fun fail(reason: String): ScreenRunState = end(ScreenRunState.Failed(reason))

    private fun end(next: ScreenRunState): ScreenRunState {
        if (current === ScreenRunState.Running) current = next
        return current
    }

    private fun timedOut(): Boolean = clock() - startedAt >= task.deadlineMs
}
