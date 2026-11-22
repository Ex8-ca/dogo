/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.agent

import app.dogo.launcher.agent.backend.AgentCatalog
import app.dogo.launcher.agent.backend.AgentConfig
import app.dogo.launcher.agent.backend.AgentKind
import app.dogo.launcher.agent.backend.AgentPreset
import app.dogo.launcher.agent.backend.ResolvedAgent
import app.dogo.launcher.agent.backend.resolve
import app.dogo.launcher.llm.HardwareProfile
import app.dogo.launcher.llm.ModelCatalog

/**
 * The user-facing knobs for the setup agent.
 *
 * Modelled as plain data so the whole policy — what is allowed, which model is
 * effective, whether the pick is safe on this device — can be unit tested
 * without a device or a UI. The Compose options sheet is a thin view over this.
 */
data class AgentOptions(
    /**
     * Which agent answers, by [AgentPreset.id]. On-device by default: a
     * launcher whose brain needs a server has no brain when the server is down.
     */
    val agentId: String = AgentCatalog.ON_DEVICE.id,
    /** Per-agent address, model and key overrides, keyed by [AgentPreset.id]. */
    val agentConfigs: Map<String, AgentConfig> = emptyMap(),
    /**
     * Chosen model, or null to follow the hardware recommendation. Null is the
     * default because a launcher that pins a model on install will pin one that
     * does not fit the phone it was installed on.
     */
    val modelId: String? = null,
    /** Effort spent per turn, sent to remote agents that support it. */
    val thinkingLevel: ThinkingLevel = ThinkingLevel.DEFAULT,
    /** Which actions the agent may perform without asking. */
    val allowedActions: Set<ActionType> = ActionType.entries.toSet(),
    /** Whether even an allowed action still needs a confirmation tap. */
    val requireConfirmation: Boolean = true,
) {

    /** The chosen agent; an unknown id (e.g. from a newer build) means on-device. */
    val agent: AgentPreset
        get() = AgentCatalog.find(agentId) ?: AgentCatalog.ON_DEVICE

    val isOnDevice: Boolean
        get() = agent.kind == AgentKind.ON_DEVICE

    /** The chosen agent with the user's overrides applied. */
    fun resolvedAgent(): ResolvedAgent = agent.resolve(agentConfigs[agent.id])

    /** [preset]'s overrides, or an empty config. */
    fun configFor(preset: AgentPreset): AgentConfig = agentConfigs[preset.id] ?: AgentConfig()

    fun withConfig(preset: AgentPreset, config: AgentConfig): AgentOptions = copy(agentConfigs = agentConfigs + (preset.id to config))

    enum class ThinkingLevel(val wireName: String, val blurb: String, val effort: String?) {
        LOW("low", "Quick answers, more battery left over.", "low"),
        DEFAULT("default", "Balanced. Use this unless you have a reason not to.", null),
        HIGH("high", "Thinks longer. Slower, and a little better at fiddly tasks.", "high"),
        ;

        companion object {
            fun fromWire(value: String?): ThinkingLevel = entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: DEFAULT
        }
    }

    /**
     * The actions a user can switch off individually.
     */
    enum class ActionType(
        val wireName: String,
        val title: String,
        val blurb: String,
    ) {
        SET_THEME(
            "set_theme",
            "Change Dogo's theme",
            "Light, dark, or follow the system, for the launcher itself.",
        ),
        SET_SYSTEM_DARK_MODE(
            "set_system_dark_mode",
            "Change the phone's dark mode",
            "System-wide dark mode. Needs Shizuku or root.",
        ),
        SET_WALLPAPER(
            "set_wallpaper",
            "Change the wallpaper",
            "Sets a flat colour background.",
        ),
        CREATE_FOLDER(
            "create_folder",
            "Group apps into folders",
            "Puts apps together on a home screen page.",
        ),
        SET_ANIMATION_SCALE(
            "set_animation_scale",
            "Change animation speed",
            "Scales window animations. Reversible in Settings.",
        ),
        ADD_TO_HOME(
            "add_to_home",
            "Add an app to the home screen",
            "Puts an icon on a page. Easily tapped off again.",
        ),
        HIDE_APP(
            "hide_app",
            "Hide an app",
            "Removes an app from the drawer. Reversible in Settings.",
        ),
        ARRANGE_RING(
            "arrange_ring",
            "Arrange the Dogo Ring",
            "Group your apps in the home-screen ring and pick its style.",
        ),
        ADD_WIDGET(
            "add_widget",
            "Add widgets",
            "Puts a widget (clock, music, calendar…) on a home screen page.",
        ),
        STYLE_HOME(
            "style_home",
            "Change the home screen layout",
            "Grid size, icon size and shape, labels, dock, search bar, accent colour.",
        ),
        SET_DOCK(
            "set_dock",
            "Choose the dock apps",
            "Replaces the apps in the dock. They stay installed and in the drawer.",
        ),
        PLUGIN(
            "plugin",
            "Run plugin actions",
            "Actions from plugins you've enabled. Each plugin's own switch still applies.",
        ),
        ;

        fun toAction(): Action = when (this) {
            SET_THEME -> Action.SetTheme("")
            SET_SYSTEM_DARK_MODE -> Action.SetSystemDarkMode(false)
            SET_WALLPAPER -> Action.SetWallpaper("")
            CREATE_FOLDER -> Action.CreateFolder("", emptyList())
            SET_ANIMATION_SCALE -> Action.SetAnimationScale(0f)
            ADD_TO_HOME -> Action.AddToHome("")
            HIDE_APP -> Action.HideApp("")
            ARRANGE_RING -> Action.ArrangeRing(emptyList(), null)
            ADD_WIDGET -> Action.AddWidget("")
            STYLE_HOME -> Action.StyleHome(HomeStyle())
            SET_DOCK -> Action.SetDock(emptyList())
            PLUGIN -> Action.Plugin("", "", "{}")
        }

        companion object {
            fun fromWire(value: String): ActionType? = entries.firstOrNull { it.wireName == value }

            /** The type covering [action], or null for [Action.Unknown]. */
            fun of(action: Action): ActionType? = when (action) {
                is Action.SetTheme -> SET_THEME
                is Action.SetSystemDarkMode -> SET_SYSTEM_DARK_MODE
                is Action.SetWallpaper -> SET_WALLPAPER
                is Action.CreateFolder -> CREATE_FOLDER
                is Action.SetAnimationScale -> SET_ANIMATION_SCALE
                is Action.AddToHome -> ADD_TO_HOME
                is Action.HideApp -> HIDE_APP
                is Action.ArrangeRing -> ARRANGE_RING
                is Action.AddWidget -> ADD_WIDGET
                is Action.StyleHome -> STYLE_HOME
                is Action.SetDock -> SET_DOCK
                is Action.Plugin -> PLUGIN
                Action.Unknown -> null
            }
        }
    }

    /** Whether [action] may be run at all, i.e. it has not been switched off. */
    fun isAllowed(action: Action): Boolean {
        val type = ActionType.of(action) ?: return false
        return type in allowedActions
    }

    /**
     * Whether [action] still needs the user to tap to confirm.
     *
     * An action the user has switched off is never prompted for: [isAllowed]
     * already refuses it, and prompting would only train people to tap through
     * a request they did not ask for.
     */
    fun needsConfirmation(action: Action): Boolean = isAllowed(action) && requireConfirmation

    /**
     * The model that will actually be used.
     *
     * Falls back to the hardware recommendation whenever the pinned pick is
     * absent, unknown, or does not fit this device — so options carried across
     * from a bigger phone cannot leave the agent with a model it cannot run.
     */
    fun resolveModel(recommendation: HardwareProfile.Recommendation): ModelCatalog.ModelVariant? {
        if (!isOnDevice) return null
        val pinned = pinnedModel() ?: return recommendation.primary
        return if (HardwareProfile.fits(pinned, recommendation.ramGb, recommendation.freeStorageGb)) {
            pinned
        } else {
            recommendation.primary
        }
    }

    /** The user's pick, or null when unset or no longer in the catalogue. */
    fun pinnedModel(): ModelCatalog.ModelVariant? = modelId?.let { id -> ModelCatalog.variants().firstOrNull { it.id == id } }

    /**
     * Problems the options sheet should surface before the agent runs.
     *
     * Returned as data rather than thrown: an impossible combination is a UI
     * state, not an error, and the launcher must keep working.
     */
    fun validate(recommendation: HardwareProfile.Recommendation): List<Issue> {
        val issues = mutableListOf<Issue>()

        if (agent.leavesDevice) {
            issues += Issue.LEAVES_DEVICE
        }

        val pinned = pinnedModel()
        if (isOnDevice && pinned == null && modelId != null) {
            issues += Issue.UNKNOWN_MODEL
        }

        if (isOnDevice && recommendation.primary == null && pinned == null) {
            issues += Issue.NO_MODEL_FITS
        }

        if (isOnDevice && pinned != null &&
            !HardwareProfile.fits(pinned, recommendation.ramGb, recommendation.freeStorageGb)
        ) {
            // The pick is real but this phone cannot run it. Say so plainly
            // rather than silently substituting something else.
            issues += Issue.MODEL_TOO_BIG
        }

        if (allowedActions.isEmpty()) {
            issues += Issue.NO_ACTIONS_ALLOWED
        }

        return issues
    }

    enum class Issue(val message: String) {
        LEAVES_DEVICE(
            "This agent runs off the phone: it needs a network connection, and " +
                "your conversation is sent to it.",
        ),
        NO_MODEL_FITS(
            "No on-device model fits this phone. Pick an off-phone agent below, " +
                "such as Hermes on your computer.",
        ),
        UNKNOWN_MODEL("The selected model is no longer in the catalogue."),
        MODEL_TOO_BIG(
            "The selected model needs more memory or storage than this phone has. " +
                "Dogo will use the model it recommends instead until you pick a " +
                "smaller one.",
        ),
        NO_ACTIONS_ALLOWED(
            "Every action is switched off, so the agent cannot actually configure " +
                "anything.",
        ),
    }
}
