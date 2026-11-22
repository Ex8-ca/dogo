/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the Dogo rebrand at runtime.
 *
 * Verifying the merged resources with aapt2 is not enough on its own: the
 * on-screen onboarding title is built from `R.string.onboarding_welcome`, and
 * that is exactly the kind of thing that can differ between what is packaged
 * and what the app actually resolves at runtime.
 */
@RunWith(AndroidJUnit4::class)
class RebrandDeviceTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun appLabelIsDogo() {
        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        assertThat(label).isEqualTo("Dogo Phone")
    }

    @Test
    fun onboardingStringsAreRebranded() {
        val welcome = context.getString(com.android.launcher3.R.string.onboarding_welcome)
        val settings = context.getString(com.android.launcher3.R.string.onboarding_open_settings_title)
        val restart = context.getString(com.android.launcher3.R.string.debug_restart_launcher)

        println("onboarding_welcome            = $welcome")
        println("onboarding_open_settings_title= $settings")
        println("debug_restart_launcher        = $restart")

        listOf(welcome, settings, restart).forEach {
            assertThat(it).doesNotContain("Lawnchair")
            assertThat(it).contains("Dogo")
        }
    }

    /** The strings above are resolved through the launcher package's R class. */
    @Test
    fun launcherComponentMatchesInstalledPackage() {
        val component = context.getString(com.android.launcher3.R.string.launcher_component)
        println("launcher_component = $component")
        assertThat(component).startsWith(context.packageName)
        assertThat(component).endsWith("app.lawnchair.LawnchairLauncher")
        assertThat(component).doesNotContain("null")
    }
}
