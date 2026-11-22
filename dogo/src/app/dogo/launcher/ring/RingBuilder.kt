/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.ring

/** Turns installed apps plus the user's grouping into the ring's slice tree. */
object RingBuilder {

    /** More than this and slices get too thin to hit with a thumb. */
    const val MAX_SLICES = 8

    const val MORE = "More"
    const val DOGO = "Dogo"

    /** An installed, launchable app. [category] is ApplicationInfo.category, or -1. */
    data class AppEntry(val pkg: String, val label: String, val category: Int = -1)

    private data class Rule(val group: String, val categories: Set<Int>, val keywords: List<String>)

    // ApplicationInfo.CATEGORY_*: GAME 0, AUDIO 1, VIDEO 2, IMAGE 3, SOCIAL 4,
    // NEWS 5, MAPS 6, PRODUCTIVITY 7, ACCESSIBILITY 8.
    private val RULES = listOf(
        Rule(
            "System",
            setOf(8),
            listOf(
                "settings", "magisk", "fdroid", "f-droid", "droidify", "neostore", "obtainium", "aurora", "shizuku",
                "stk", "uiautomator", "ssh", "updater", "setupwizard",
            ),
        ),
        Rule(
            "People",
            setOf(4),
            listOf(
                "dialer", "contacts", "messag", "mms", "sms", "mail", "signal", "telegram", "whatsapp",
                "thoughtcrime", "k9", "element", "matrix", "discord", "jami", "conversations",
            ),
        ),
        Rule(
            "Media",
            setOf(0, 1, 2, 3),
            listOf(
                "camera", "aperture", "gallery", "glimpse", "photo", "music", "twelve", "recorder", "audiofx",
                "video", "player", "newpipe", "youtube", "podcast", "spotify", "vlc", "game",
            ),
        ),
        Rule(
            "Web",
            setOf(5, 6),
            listOf("browser", "jelly", "firefox", "fennec", "chrome", "mull", "duckduckgo", "maps", "osmand", "news", "rss", "reddit"),
        ),
        Rule(
            "Tools",
            setOf(7),
            listOf(
                "calculator", "calendar", "etar", "clock", "notes", "files", "documentsui", "termux", "todo", "task",
                "keep", "editor", "assistant", "gpt", "weather",
            ),
        ),
    )

    private val GROUP_ORDER = listOf("People", "Web", "Media", "Tools", "System", MORE)

    /** Groups apps by what they are for. Dogo itself is left out. */
    fun automaticGroups(apps: List<AppEntry>, ownPackage: String = "app.dogo.launcher"): List<RingGroup> {
        val buckets = linkedMapOf<String, MutableList<AppEntry>>()
        apps.filterNot { it.pkg.startsWith(ownPackage) }.distinctBy { it.pkg }.forEach { app ->
            buckets.getOrPut(groupFor(app)) { mutableListOf() } += app
        }
        return GROUP_ORDER.mapNotNull { name ->
            buckets[name]?.sortedBy { it.label.lowercase() }?.let { RingGroup(name, it.map(AppEntry::pkg)) }
        }
    }

    internal fun groupFor(app: AppEntry): String {
        val haystack = (app.pkg + " " + app.label).lowercase()
        // An app's own declared category beats keyword guesses.
        RULES.firstOrNull { app.category in it.categories }?.let { return it.group }
        return RULES.firstOrNull { rule -> rule.keywords.any { it in haystack } }?.group ?: MORE
    }

    /**
     * The root ring: one slice per group (a group of one app becomes that app),
     * then a Dogo slice. Packages that aren't installed are skipped.
     */
    fun build(groups: List<RingGroup>, apps: List<AppEntry>): RingItem.Group {
        val byPkg = apps.associateBy { it.pkg }
        val slices = groups.mapNotNull { group ->
            val members = group.apps.distinct().mapNotNull { byPkg[it] }.map { RingItem.App(it.pkg, it.label) }
            when (members.size) {
                0 -> null
                1 -> members.single()
                else -> RingItem.Group(group.name, chunk(members))
            }
        }
        val dogo = RingItem.Group(
            DOGO,
            listOf(
                RingItem.Command(RingCommand.TALK, "Talk"),
                RingItem.Command(RingCommand.ASK_DOGO, "Chat"),
                RingItem.Command(RingCommand.APP_DRAWER, "All apps"),
                RingItem.Command(RingCommand.HOME_OPTIONS, "Home"),
                RingItem.Command(RingCommand.DOGO_OPTIONS, "Options"),
            ),
        )
        return RingItem.Group(DOGO, chunk(slices, reserve = 1) + dogo)
    }

    /**
     * At most [MAX_SLICES] - [reserve] items; the rest go into a nested "More"
     * slice, recursively.
     */
    internal fun chunk(items: List<RingItem>, reserve: Int = 0): List<RingItem> {
        val room = MAX_SLICES - reserve
        if (items.size <= room) return items
        return items.take(room - 1) + RingItem.Group(MORE, chunk(items.drop(room - 1)))
    }
}
