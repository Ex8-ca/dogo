/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.screenuse

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.android.launcher3.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug builds only, and only callable from adb: the manifest requires the DUMP permission, which
 * apps cannot hold. Runs a scripted task through the real controller, driver and policy so the
 * phone-use path can be tried on a real app before any agent is connected.
 *
 * `adb shell am broadcast -n <app id>/app.dogo.launcher.screenuse.ScreenUseDebugReceiver \
 *    --es package com.android.calculator2 --es taps '7|plus,+|8|equals,='`
 *
 * Each `|`-separated entry is one press; `,` lists the labels that entry may match.
 */
class ScreenUseDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val pkg = intent.getStringExtra("package")?.trim().orEmpty()
        val script = intent.getStringExtra("taps").orEmpty().split('|').map { entry -> entry.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() } }.filter { it.isNotEmpty() }
        if (pkg.isEmpty()) return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val driver = AccessibilityScreenDriver(app)
                var next = 0
                val planner = ScreenPlanner { _, snapshot, _ ->
                    if (next >= script.size) return@ScreenPlanner PlannedStep.Done("Pressed ${script.size} buttons.")
                    val wanted = script[next]
                    val hit = snapshot.elements.firstOrNull { e -> e.clickable && (e.text.lowercase() in wanted || e.description.lowercase() in wanted) }
                        ?: return@ScreenPlanner PlannedStep.GiveUp("Couldn't find a button for step ${next + 1}.")
                    next++
                    PlannedStep.Act(ScreenStep.Tap(hit.id), "press ${hit.id}")
                }
                val refuse = StepApprover { _, _, _, _ -> false }
                val task = ScreenTask(pkg, "debug run: ${script.size} presses", maxSteps = script.size + 2, deadlineMs = 60_000)
                val out = ScreenController(driver, planner, refuse).run(task)
                Log.i(TAG, "outcome state=${out.state} steps=${out.steps} summary=${out.summary}")
                driver.snapshot()?.let { Log.i(TAG, "final screen:\n${it.render()}") } ?: Log.i(TAG, "final screen: not readable")
            } catch (e: Exception) {
                Log.w(TAG, "debug run failed: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "DogoScreenUse"
    }
}
