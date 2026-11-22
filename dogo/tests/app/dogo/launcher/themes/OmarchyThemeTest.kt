/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.themes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OmarchyThemeTest {

    /** Trimmed from omacom/omarchy themes/catppuccin/colors.toml (MIT). */
    private val catppuccin = """
        mode = "dark"

        accent = "#89b4fa"
        selection = "#45475a"  # inline comment
        background = "#1e1e2e"
        foreground = "#cdd6f4"
        red = "#f38ba8"
    """.trimIndent()

    @Test
    fun `parses an Omarchy colors toml`() {
        val theme = OmarchyTheme.parse("Catppuccin", catppuccin)!!
        assertThat(theme.dark).isTrue()
        assertThat(theme.accent).isEqualTo(0xFF89B4FA.toInt())
        assertThat(theme.background).isEqualTo(0xFF1E1E2E.toInt())
        assertThat(theme.colors["selection"]).isEqualTo(0xFF45475A.toInt())
        assertThat(theme.colors["red"]).isEqualTo(0xFFF38BA8.toInt())
    }

    @Test
    fun `older themes without mode or accent still work`() {
        // Pre-v4 themes used color0..color15 and no mode line.
        val old = """
            foreground = "#4c4f69"
            background = "#eff1f5"
            color4 = "#1e66f5"
        """.trimIndent()
        val theme = OmarchyTheme.parse("Latte", old)!!
        assertThat(theme.dark).isFalse()
        assertThat(theme.accent).isEqualTo(0xFF1E66F5.toInt())
    }

    @Test
    fun `a file without the core colours is rejected, not guessed`() {
        assertThat(OmarchyTheme.parse("x", "mode = \"dark\"\naccent = \"#ffffff\"")).isNull()
        assertThat(OmarchyTheme.parse("x", "")).isNull()
        assertThat(OmarchyTheme.parse("x", "not toml at all {{{")).isNull()
    }

    @Test
    fun `comments are stripped but hash colours are kept`() {
        assertThat(OmarchyTheme.stripComment("""a = "#fff" # note""")).isEqualTo("""a = "#fff" """)
        assertThat(OmarchyTheme.stripComment("# whole line")).isEmpty()
    }

    @Test
    fun `colour formats`() {
        assertThat(OmarchyTheme.parseColor("#fff")).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(OmarchyTheme.parseColor("#80112233")).isEqualTo(0xFF112233.toInt())
        assertThat(OmarchyTheme.parseColor("#12345")).isNull()
        assertThat(OmarchyTheme.parseColor("#gggggg")).isNull()
    }

    @Test
    fun `display names`() {
        assertThat(OmarchyTheme.displayName("tokyo-night")).isEqualTo("Tokyo Night")
        assertThat(OmarchyTheme.displayName("retro-82")).isEqualTo("Retro 82")
    }

    @Test
    fun `community theme references`() {
        val source = OmarchyThemeSource()
        val a = source.parseCommunityRef("dhh/omarchy-giants-theme")!!
        assertThat(a.repo).isEqualTo("dhh/omarchy-giants-theme")
        assertThat(a.name).isEqualTo("Giants")
        assertThat(a.rawBase).isEqualTo("https://raw.githubusercontent.com/dhh/omarchy-giants-theme/HEAD")

        val b = source.parseCommunityRef("https://github.com/omacom/omarchy/tree/quattro/themes/nord")!!
        assertThat(b.repo).isEqualTo("omacom/omarchy")
        assertThat(b.branch).isEqualTo("quattro")
        assertThat(b.path).isEqualTo("themes/nord")
        assertThat(b.name).isEqualTo("Nord")

        assertThat(source.parseCommunityRef("https://gitlab.com/a/b")).isNull()
        assertThat(source.parseCommunityRef("just-a-word")).isNull()
    }
}
