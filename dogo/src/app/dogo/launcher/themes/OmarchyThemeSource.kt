/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.themes

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.theme.color.ColorOption
import app.lawnchair.ui.preferences.components.ThemeChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/**
 * Lists and applies Omarchy themes.
 *
 * The official themes live in the Omarchy repository (MIT). A community theme
 * is any GitHub repo with a `colors.toml` at its root, which is how Omarchy
 * itself installs them, so `owner/repo` or a GitHub URL works too.
 */
class OmarchyThemeSource(
    private val client: OkHttpClient = OkHttpClient(),
    private val officialRepo: String = OFFICIAL_REPO,
    private val officialBranch: String = OFFICIAL_BRANCH,
) {

    /** Where a theme's files are, relative to raw.githubusercontent.com. */
    data class ThemeRef(val name: String, val repo: String, val branch: String, val path: String) {
        val rawBase: String get() = "https://raw.githubusercontent.com/$repo/$branch" + (if (path.isEmpty()) "" else "/$path")
        val apiBase: String get() = "https://api.github.com/repos/$repo/contents" + (if (path.isEmpty()) "" else "/$path")
    }

    data class Loaded(val ref: ThemeRef, val theme: OmarchyTheme, val wallpaperUrls: List<String>)

    suspend fun listOfficial(): Result<List<ThemeRef>> = runCatching {
        val listing = JSONArray(get("https://api.github.com/repos/$officialRepo/contents/themes?ref=$officialBranch"))
        (0 until listing.length())
            .map { listing.getJSONObject(it) }
            .filter { it.optString("type") == "dir" }
            .map { dir ->
                ThemeRef(OmarchyTheme.displayName(dir.getString("name")), officialRepo, officialBranch, "themes/${dir.getString("name")}")
            }
            .sortedBy { it.name }
    }

    /** Resolves `owner/repo`, `https://github.com/owner/repo[/tree/branch[/path]]`. */
    fun parseCommunityRef(input: String): ThemeRef? {
        val trimmed = input.trim().removeSuffix("/").removeSuffix(".git")
        val ownerRepo = Regex("^([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)$").matchEntire(trimmed)
        if (ownerRepo != null) {
            val (owner, repo) = ownerRepo.destructured
            return ThemeRef(themeNameFromRepo(repo), "$owner/$repo", "HEAD", "")
        }
        val url = (if (trimmed.contains("://")) trimmed else "https://$trimmed").toHttpUrlOrNull() ?: return null
        if (url.host != "github.com" && url.host != "www.github.com") return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        if (segments.size < 2) return null
        val repo = segments[1].removeSuffix(".git")
        val (branch, path) = if (segments.size >= 4 && segments[2] == "tree") {
            segments[3] to segments.drop(4).joinToString("/")
        } else {
            "HEAD" to ""
        }
        return ThemeRef(themeNameFromRepo(if (path.isNotEmpty()) path.substringAfterLast('/') else repo), "${segments[0]}/$repo", branch, path)
    }

    suspend fun load(ref: ThemeRef): Result<Loaded> = runCatching {
        val toml = get("${ref.rawBase}/colors.toml")
        val theme = OmarchyTheme.parse(ref.name, toml)
            ?: error("${ref.repo} has a colors.toml, but no background/foreground colours in it.")
        val wallpapers = runCatching {
            val listing = JSONArray(get("${ref.apiBase}/backgrounds?ref=${ref.branch}"))
            (0 until listing.length())
                .map { listing.getJSONObject(it) }
                .filter { it.optString("type") == "file" && it.optString("name").substringAfterLast('.').lowercase() in IMAGE_TYPES }
                .sortedBy { it.optString("name") }
                .mapNotNull { it.optString("download_url").takeIf(String::isNotBlank) }
        }.getOrDefault(emptyList())
        Loaded(ref, theme, wallpapers)
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).header("User-Agent", "Dogo").build()).execute().use { response ->
            if (response.code == 404) error("Not found: $url. Is this an Omarchy theme repo with a colors.toml?")
            if (!response.isSuccessful) error("GitHub returned HTTP ${response.code} for $url")
            response.body.string()
        }
    }

    private suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).header("User-Agent", "Dogo").build()).execute().use { response ->
            if (!response.isSuccessful) error("Couldn't download the wallpaper (HTTP ${response.code}).")
            response.body.bytes()
        }
    }

    /**
     * Applies [loaded] to Dogo: light/dark, accent colour, and (optionally)
     * the first wallpaper. Only touches Dogo and the wallpaper; the phone's
     * own dark mode is a separate, privileged action.
     */
    suspend fun apply(context: Context, loaded: Loaded, setWallpaper: Boolean): String {
        val app = context.applicationContext
        withContext(Dispatchers.Main) {
            PreferenceManager.getInstance(app).launcherTheme.set(if (loaded.theme.dark) ThemeChoice.DARK else ThemeChoice.LIGHT)
        }
        PreferenceManager2.getInstance(app).accentColor.set(ColorOption.CustomColor(loaded.theme.accent))
        var wallpaperNote = ""
        if (setWallpaper) {
            val url = loaded.wallpaperUrls.firstOrNull()
            if (url == null) {
                wallpaperNote = " (this theme has no wallpaper)"
            } else {
                val bytes = getBytes(url)
                withContext(Dispatchers.IO) {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: error("The wallpaper isn't an image Android can read.")
                    WallpaperManager.getInstance(app).setBitmap(bitmap)
                    bitmap.recycle()
                }
                wallpaperNote = " and its wallpaper"
            }
        }
        return "Applied ${loaded.theme.name}$wallpaperNote."
    }

    companion object {
        const val OFFICIAL_REPO = "omacom/omarchy"
        const val OFFICIAL_BRANCH = "quattro"
        private val IMAGE_TYPES = setOf("png", "jpg", "jpeg", "webp")

        private fun themeNameFromRepo(repo: String): String = OmarchyTheme.displayName(repo.removePrefix("omarchy-").removeSuffix("-theme"))
    }
}
