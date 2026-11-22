/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.screenuse

import kotlinx.coroutines.CancellationException

/** What the agent wants next. [why] is the agent's own words, shown to the user. */
sealed interface PlannedStep {
    data class Act(val step: ScreenStep, val why: String = "") : PlannedStep

    data class Done(val summary: String) : PlannedStep

    data class GiveUp(val reason: String) : PlannedStep
}

/** Picks one step at a time. [history] holds past steps by id and role only, never screen text. */
fun interface ScreenPlanner {
    suspend fun next(task: ScreenTask, snapshot: ScreenSnapshot, history: List<String>): PlannedStep
}

/** Asks the user about one step that needs its own card. [label] is screen text: show it as data. */
fun interface StepApprover {
    suspend fun approve(task: ScreenTask, step: ScreenStep, label: String?, reason: String): Boolean
}

data class ScreenOutcome(val state: ScreenRunState, val steps: Int, val summary: String? = null)

/**
 * Runs one approved [ScreenTask]. Every step goes through [ScreenPolicy] first, so nothing the
 * agent proposes, and nothing it reads off the screen, can skip a rule. Each turn re-checks the
 * clock, the lock and the foreground app through [ScreenTaskRun].
 */
class ScreenController(
    private val driver: ScreenDriver,
    private val planner: ScreenPlanner,
    private val approver: StepApprover,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    @Volatile
    private var current: ScreenTaskRun? = null

    /** The Stop button. Safe from any thread; the loop ends before its next step. */
    fun stop() {
        current?.stop()
    }

    suspend fun run(task: ScreenTask): ScreenOutcome {
        val run = ScreenTaskRun(task, clock)
        current = run
        try {
            return loop(task, run)
        } catch (e: CancellationException) {
            run.stop()
            throw e
        } finally {
            current = null
        }
    }

    private suspend fun loop(task: ScreenTask, run: ScreenTaskRun): ScreenOutcome {
        if (ScreenPolicy.isDeniedPackage(task.appPackage)) return end(run, "That app is on the never-drive list.")
        if (!driver.isReady) return end(run, "Turn on the Dogo accessibility service first.")
        if (!driver.open(task.appPackage)) return end(run, "Couldn't open that app.")

        val history = ArrayList<String>()
        var refused = 0
        var failed = 0
        var summary: String? = null

        while (run.isRunning) {
            val seen = driver.snapshot() ?: return end(run, "Couldn't read the screen.")
            if (run.observe(seen.packageName, driver.isLocked).isTerminal) break

            when (val next = planner.next(task, seen, history.takeLast(HISTORY))) {
                is PlannedStep.Done -> {
                    summary = next.summary
                    run.finish()
                }

                is PlannedStep.GiveUp -> {
                    summary = next.reason
                    run.fail(next.reason)
                }

                is PlannedStep.Act -> {
                    val allowed = checked(task, next.step, seen)
                    if (allowed != null) {
                        history += "not done: $allowed"
                        if (++refused >= MAX_REFUSALS) return end(run, "Stopped after several refused steps.")
                    } else if (!run.recordStep()) {
                        break
                    } else {
                        refused = 0
                        when (val result = driver.perform(next.step, seen)) {
                            StepResult.Done -> {
                                failed = 0
                                history += "did: ${describe(next.step)}"
                            }

                            is StepResult.Failed -> {
                                history += "failed: ${describe(next.step)} (${result.reason})"
                                if (++failed >= MAX_FAILURES) return end(run, "Stopped after several steps that didn't work.")
                            }
                        }
                    }
                }
            }
        }
        // A run that ended for any reason is judged once more, so the final state is the true one.
        if (run.isRunning) run.observe(driver.snapshot()?.packageName, driver.isLocked)
        return ScreenOutcome(run.state, run.stepsTaken, summary)
    }

    /** Null when the step may go ahead; otherwise why it did not. */
    private suspend fun checked(task: ScreenTask, step: ScreenStep, seen: ScreenSnapshot): String? = when (val decision = ScreenPolicy.decide(task, step, seen)) {
        PolicyDecision.Allowed -> null

        is PolicyDecision.Denied -> decision.reason

        is PolicyDecision.NeedsApproval ->
            if (approver.approve(task, step, ScreenPolicy.labelOf(step, seen), decision.reason)) null else "the user said no"
    }

    private fun end(run: ScreenTaskRun, reason: String): ScreenOutcome {
        run.fail(reason)
        return ScreenOutcome(run.state, run.stepsTaken, reason)
    }

    /** Ids and roles only. On-screen words never go back into the agent's history. */
    private fun describe(step: ScreenStep): String = when (step) {
        is ScreenStep.Tap -> "tap ${step.nodeId}"
        is ScreenStep.Type -> "type into ${step.nodeId}"
        is ScreenStep.Scroll -> "scroll ${step.direction.name.lowercase()}"
        ScreenStep.Back -> "back"
        ScreenStep.Home -> "home"
        ScreenStep.Wait -> "wait"
    }

    private companion object {
        const val HISTORY = 12
        const val MAX_REFUSALS = 4
        const val MAX_FAILURES = 3
    }
}
