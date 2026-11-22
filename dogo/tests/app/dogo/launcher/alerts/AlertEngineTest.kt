/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.alerts

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class AlertEngineTest {
    private val candidate = AlertCandidate("key", "mail", "Mail", "Anna", "Hello", "Anna", null, false, "msg", 1000)

    @Test
    fun `noise and own notifications never reach scorer`() = runBlocking {
        var calls = 0
        val engine = AlertEngine("dogo") {
            calls++
            AlertScore.INTERRUPT
        }
        listOf(
            candidate.copy(ongoing = true),
            candidate.copy(groupSummary = true),
            candidate.copy(media = true),
            candidate.copy(packageName = "dogo"),
        ).forEach { assertThat(engine.evaluate(it, AlertRules(), 720, 1000)).isNull() }
        assertThat(calls).isEqualTo(0)
    }

    @Test
    fun `duplicate update and sender cooldown avoid extra model calls`() = runBlocking {
        var calls = 0
        val engine = AlertEngine("dogo") {
            calls++
            AlertScore.NOTIFY
        }
        assertThat(engine.evaluate(candidate, AlertRules(), 720, 1000)?.score).isEqualTo(AlertScore.NOTIFY)
        assertThat(engine.evaluate(candidate, AlertRules(), 720, 2000)).isNull()
        assertThat(engine.evaluate(candidate.copy(key = "second", text = "new"), AlertRules(), 720, 3000)?.score).isEqualTo(AlertScore.DIGEST)
        assertThat(calls).isEqualTo(1)
        assertThat(engine.evaluate(candidate.copy(text = "changed"), AlertRules(), 720, 70000)?.score).isEqualTo(AlertScore.NOTIFY)
    }

    @Test
    fun `muting takes precedence over VIP and keywords`() = runBlocking {
        val engine = AlertEngine("dogo") { error("must not score") }
        val rules = AlertRules(vips = setOf("anna"), keywords = setOf("hello"), mutedSenders = setOf("anna"))
        assertThat(engine.evaluate(candidate, rules, 720, 1000)?.score).isEqualTo(AlertScore.IGNORE)
    }

    @Test
    fun `quiet hours cap VIP at digest including midnight crossing`() = runBlocking {
        val engine = AlertEngine("dogo") { error("must not score") }
        val rules = AlertRules(vips = setOf("anna"), quietHours = QuietHours(1320, 420))
        val result = engine.evaluate(candidate, rules, 60, 1000)
        assertThat(result?.score).isEqualTo(AlertScore.DIGEST)
        assertThat(result?.reasons).contains(AlertReason.QuietHours)
    }

    @Test
    fun `scorer failure retains a quiet default decision`() = runBlocking {
        val engine = AlertEngine("dogo") { null }
        val result = engine.evaluate(candidate, AlertRules(), 720, 1000)
        assertThat(result?.score).isEqualTo(AlertScore.DIGEST)
        assertThat(result?.decidedBy).isEqualTo(Decider.DEFAULT)
    }

    @Test
    fun `app overrides and keywords are decided without model`() = runBlocking {
        val engine = AlertEngine("dogo") { error("must not score") }
        assertThat(engine.evaluate(candidate, AlertRules(appOverrides = mapOf("mail" to AlertScore.IGNORE)), 720, 1000)?.score).isEqualTo(AlertScore.IGNORE)
        assertThat(engine.evaluate(candidate.copy(key = "other", sender = "Ben"), AlertRules(keywords = setOf("hello")), 720, 1000)?.score).isEqualTo(AlertScore.NOTIFY)
    }

    @Test
    fun `throwing scorer does not lose notification`() = runBlocking {
        val engine = AlertEngine("dogo") { throw IllegalStateException("offline") }
        assertThat(engine.evaluate(candidate, AlertRules(), 720, 1000)?.score).isEqualTo(AlertScore.DIGEST)
    }
}
