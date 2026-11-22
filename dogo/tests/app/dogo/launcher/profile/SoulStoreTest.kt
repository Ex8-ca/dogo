/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.profile

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SoulStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = SoulStore(File(tmp.root, "soul"))

    @Test
    fun `starts with Dogo's own soul`() {
        val s = store().read()
        assertThat(s.source).isEqualTo(SoulStore.Source.BUILT_IN)
        assertThat(s.text).isEqualTo(DefaultSoul.TEXT)
    }

    @Test
    fun `a Hermes soul replaces it and survives a restart`() {
        store().saveFromHermes("# Mine\n\nBe direct and kind.", 42L)
        val again = store().read()
        assertThat(again.source).isEqualTo(SoulStore.Source.HERMES)
        assertThat(again.text).isEqualTo("# Mine\n\nBe direct and kind.")
        assertThat(again.syncedAt).isEqualTo(42L)
    }

    @Test
    fun `syncing again overwrites the earlier copy`() {
        val s = store()
        s.saveFromHermes("first soul text here", 1L)
        s.saveFromHermes("second soul text here", 2L)
        assertThat(s.read().text).isEqualTo("second soul text here")
    }

    @Test
    fun `reset goes back to Dogo's own`() {
        val s = store()
        s.saveFromHermes("a soul from hermes", 1L)
        s.reset()
        assertThat(s.read().source).isEqualTo(SoulStore.Source.BUILT_IN)
    }

    @Test
    fun `a damaged file falls back instead of failing`() {
        val dir = File(tmp.root, "soul").apply { mkdirs() }
        File(dir, "soul.json").writeText("{not json")
        assertThat(store().read().source).isEqualTo(SoulStore.Source.BUILT_IN)
    }

    @Test
    fun `an empty soul is refused and changes nothing`() {
        val s = store()
        s.saveFromHermes("a soul from hermes", 1L)
        runCatching { s.saveFromHermes("   \n  ", 2L) }
        assertThat(s.read().text).isEqualTo("a soul from hermes")
    }
}
