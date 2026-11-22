/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.distribution

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FdroidHelperTest {

    @Test
    fun `f-droid package name is correct`() {
        assertThat(FdroidHelper.FDROID_PACKAGE).isEqualTo("org.fdroid.fdroid")
    }

    @Test
    fun `f-droid client package name is correct`() {
        assertThat(FdroidHelper.FDROID_CLIENT_PACKAGE).isEqualTo("org.fdroid.client")
    }

    @Test
    fun `download url is the official https one`() {
        assertThat(FdroidHelper.FDROID_DOWNLOAD_URL).isEqualTo("https://f-droid.org/F-Droid.apk")
    }

    @Test
    fun `download url is never http`() {
        assertThat(FdroidHelper.FDROID_DOWNLOAD_URL).startsWith("https://")
    }

    @Test
    fun `known store packages include f-droid client and obtanium`() {
        assertThat(FdroidHelper.STORE_PACKAGES)
            .containsAtLeast(
                FdroidHelper.FDROID_CLIENT_PACKAGE,
                FdroidHelper.OBTAINIUM_PACKAGE,
            )
    }

    @Test
    fun `store packages have no duplicates`() {
        assertThat(FdroidHelper.STORE_PACKAGES.toSet()).hasSize(FdroidHelper.STORE_PACKAGES.size)
    }
}
