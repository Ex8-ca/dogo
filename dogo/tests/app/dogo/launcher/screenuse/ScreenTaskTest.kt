/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.screenuse

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ScreenTaskTest {

    private val app = "com.android.deskclock"

    private class FakeClock(var now: Long = 1_000_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    private fun newRun(
        clock: FakeClock = FakeClock(),
        maxSteps: Int = 3,
        deadlineMs: Long = 60_000L,
    ) = ScreenTaskRun(ScreenTask(app, "set a timer", maxSteps, deadlineMs), clock)

    // ---- ScreenTask ----

    @Test
    fun `defaults are 20 steps and 5 minutes`() {
        val t = ScreenTask(app, "goal")
        assertThat(t.maxSteps).isEqualTo(20)
        assertThat(t.deadlineMs).isEqualTo(5 * 60 * 1000L)
    }

    @Test
    fun `limits are capped at 40 steps and 15 minutes`() {
        val t = ScreenTask(app, "goal", maxSteps = 1_000, deadlineMs = 24 * 60 * 60 * 1000L)
        assertThat(t.maxSteps).isEqualTo(40)
        assertThat(t.deadlineMs).isEqualTo(15 * 60 * 1000L)
    }

    @Test
    fun `limits at or below zero are raised to a minimum instead of meaning no limit`() {
        val t = ScreenTask(app, "goal", maxSteps = 0, deadlineMs = -5)
        assertThat(t.maxSteps).isEqualTo(1)
        assertThat(t.deadlineMs).isEqualTo(ScreenTask.MIN_DEADLINE_MS)
    }

    @Test
    fun `package and goal are trimmed, a blank one is rejected`() {
        val t = ScreenTask("  $app ", "  do it  ")
        assertThat(t.appPackage).isEqualTo(app)
        assertThat(t.goal).isEqualTo("do it")
        assertThrows(IllegalArgumentException::class.java) { ScreenTask("  ", "goal") }
        assertThrows(IllegalArgumentException::class.java) { ScreenTask(app, "   ") }
    }

    @Test
    fun `a very long goal is cut`() {
        assertThat(ScreenTask(app, "g".repeat(10_000)).goal.length).isEqualTo(ScreenTask.MAX_GOAL_CHARS)
    }

    @Test
    fun `tasks with the same values are equal`() {
        assertThat(ScreenTask(app, "g", 5, 2000)).isEqualTo(ScreenTask(app, "g", 5, 2000))
        assertThat(ScreenTask(app, "g", 5, 2000)).isNotEqualTo(ScreenTask(app, "g", 6, 2000))
    }

    // ---- run: happy path ----

    @Test
    fun `a new run is Running with no steps`() {
        val r = newRun()
        assertThat(r.state).isEqualTo(ScreenRunState.Running)
        assertThat(r.isRunning).isTrue()
        assertThat(r.stepsTaken).isEqualTo(0)
        assertThat(r.remainingMs()).isEqualTo(60_000L)
    }

    @Test
    fun `steps are counted while the run is healthy`() {
        val r = newRun()
        assertThat(r.recordStep()).isTrue()
        assertThat(r.recordStep()).isTrue()
        assertThat(r.stepsTaken).isEqualTo(2)
        assertThat(r.observe(app, locked = false)).isEqualTo(ScreenRunState.Running)
        assertThat(r.state).isEqualTo(ScreenRunState.Running)
    }

    @Test
    fun `finish ends the run as Finished`() {
        val r = newRun()
        r.recordStep()
        assertThat(r.finish()).isEqualTo(ScreenRunState.Finished)
        assertThat(r.state).isEqualTo(ScreenRunState.Finished)
    }

    // ---- run: step limit ----

    @Test
    fun `exactly maxSteps steps are accepted, the next one ends the run`() {
        val r = newRun(maxSteps = 3)
        assertThat(r.recordStep()).isTrue()
        assertThat(r.recordStep()).isTrue()
        assertThat(r.recordStep()).isTrue()
        assertThat(r.state).isEqualTo(ScreenRunState.Running)
        assertThat(r.recordStep()).isFalse()
        assertThat(r.state).isEqualTo(ScreenRunState.StepLimit)
        assertThat(r.stepsTaken).isEqualTo(3)
    }

    @Test
    fun `the step limit cannot be raised past the cap by asking for more`() {
        val r = ScreenTaskRun(ScreenTask(app, "goal", maxSteps = 500), FakeClock())
        repeat(40) { assertThat(r.recordStep()).isTrue() }
        assertThat(r.recordStep()).isFalse()
        assertThat(r.state).isEqualTo(ScreenRunState.StepLimit)
    }

    // ---- run: timeout ----

    @Test
    fun `time passing counts down and the deadline ends the run`() {
        val clock = FakeClock()
        val r = newRun(clock, deadlineMs = 10_000)
        clock.now += 4_000
        assertThat(r.remainingMs()).isEqualTo(6_000L)
        assertThat(r.recordStep()).isTrue()
        clock.now += 6_000
        assertThat(r.remainingMs()).isEqualTo(0L)
        assertThat(r.recordStep()).isFalse()
        assertThat(r.state).isEqualTo(ScreenRunState.Timeout)
    }

    @Test
    fun `observe notices a timeout too`() {
        val clock = FakeClock()
        val r = newRun(clock, deadlineMs = 10_000)
        clock.now += 10_001
        assertThat(r.observe(app, locked = false)).isEqualTo(ScreenRunState.Timeout)
    }

    @Test
    fun `just before the deadline the run goes on`() {
        val clock = FakeClock()
        val r = newRun(clock, deadlineMs = 10_000)
        clock.now += 9_999
        assertThat(r.recordStep()).isTrue()
        assertThat(r.observe(app, locked = false)).isEqualTo(ScreenRunState.Running)
    }

    @Test
    fun `a clock that jumps backwards does not end the run or extend it past the deadline`() {
        val clock = FakeClock()
        val r = newRun(clock, deadlineMs = 10_000)
        clock.now -= 5_000
        assertThat(r.recordStep()).isTrue()
        assertThat(r.remainingMs()).isAtMost(15_000L)
    }

    // ---- run: leaving the app and the lock ----

    @Test
    fun `another app in front ends the run with LeftApp`() {
        val r = newRun()
        assertThat(r.observe("com.android.chrome", locked = false)).isEqualTo(ScreenRunState.LeftApp)
        assertThat(r.state).isEqualTo(ScreenRunState.LeftApp)
    }

    @Test
    fun `an unknown foreground package counts as left`() {
        assertThat(newRun().observe(null, locked = false)).isEqualTo(ScreenRunState.LeftApp)
        assertThat(newRun().observe("  ", locked = false)).isEqualTo(ScreenRunState.LeftApp)
    }

    @Test
    fun `a look-alike package is not the approved app`() {
        assertThat(newRun().observe("$app.evil", locked = false)).isEqualTo(ScreenRunState.LeftApp)
    }

    @Test
    fun `the screen locking ends the run, even in the right app`() {
        val r = newRun()
        assertThat(r.observe(app, locked = true)).isEqualTo(ScreenRunState.ScreenLocked)
        assertThat(r.state).isEqualTo(ScreenRunState.ScreenLocked)
    }

    @Test
    fun `lock wins over leaving the app, because the keyguard is another package`() {
        assertThat(newRun().observe("com.android.systemui", locked = true)).isEqualTo(ScreenRunState.ScreenLocked)
    }

    // ---- run: stop and failure ----

    @Test
    fun `stop ends the run as Stopped`() {
        val r = newRun()
        r.recordStep()
        assertThat(r.stop()).isEqualTo(ScreenRunState.Stopped)
        assertThat(r.state).isEqualTo(ScreenRunState.Stopped)
        assertThat(r.isRunning).isFalse()
    }

    @Test
    fun `fail keeps its reason`() {
        val r = newRun()
        assertThat(r.fail("service gone")).isEqualTo(ScreenRunState.Failed("service gone"))
        assertThat(r.state).isEqualTo(ScreenRunState.Failed("service gone"))
    }

    // ---- terminal states ----

    private fun ended(): List<Pair<String, ScreenTaskRun>> = listOf(
        "Stopped" to newRun().also { it.stop() },
        "Finished" to newRun().also { it.finish() },
        "StepLimit" to newRun(maxSteps = 1).also {
            it.recordStep()
            it.recordStep()
        },
        "Timeout" to FakeClock().let { c ->
            newRun(c, deadlineMs = 1000).also {
                c.now += 5000
                it.recordStep()
            }
        },
        "LeftApp" to newRun().also { it.observe("other.app", false) },
        "ScreenLocked" to newRun().also { it.observe(app, true) },
        "Failed" to newRun().also { it.fail("boom") },
    )

    @Test
    fun `every non-Running state is terminal and rejects further steps`() {
        for ((name, r) in ended()) {
            val state = r.state
            assertThat(state.isTerminal).isTrue()
            val before = r.stepsTaken
            assertThat(r.recordStep()).isFalse()
            assertThat(r.stepsTaken).isEqualTo(before)
            assertThat(r.state).isEqualTo(state)
            assertThat(name).isNotEmpty()
        }
        assertThat(ScreenRunState.Running.isTerminal).isFalse()
    }

    @Test
    fun `no later event changes a terminal state`() {
        for ((_, r) in ended()) {
            val state = r.state
            assertThat(r.stop()).isEqualTo(state)
            assertThat(r.finish()).isEqualTo(state)
            assertThat(r.fail("again")).isEqualTo(state)
            assertThat(r.observe(app, locked = false)).isEqualTo(state)
            assertThat(r.observe("elsewhere", locked = true)).isEqualTo(state)
            assertThat(r.state).isEqualTo(state)
        }
    }

    @Test
    fun `a stopped run cannot be revived by coming back to the app`() {
        val r = newRun()
        r.stop()
        assertThat(r.observe(app, locked = false)).isEqualTo(ScreenRunState.Stopped)
        assertThat(r.recordStep()).isFalse()
    }

    @Test
    fun `a run that left the app stays ended when the app returns`() {
        val r = newRun()
        r.observe("com.android.chrome", locked = false)
        assertThat(r.observe(app, locked = false)).isEqualTo(ScreenRunState.LeftApp)
        assertThat(r.recordStep()).isFalse()
    }

    @Test
    fun `the first ending wins`() {
        val r = newRun(maxSteps = 1)
        r.recordStep()
        r.observe("other.app", false)
        r.recordStep()
        r.stop()
        assertThat(r.state).isEqualTo(ScreenRunState.LeftApp)
    }
}
