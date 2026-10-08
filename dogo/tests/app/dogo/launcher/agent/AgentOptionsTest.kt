package app.dogo.launcher.agent

import app.dogo.launcher.agent.Action
import app.dogo.launcher.agent.ActionExecutor
import app.dogo.launcher.llm.HardwareProfile
import app.dogo.launcher.llm.ModelCatalog
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AgentOptionsTest {

    private val biggest = ModelCatalog.variants().maxBy { it.sizeBytes }
    private val smallest = ModelCatalog.variants().minBy { it.sizeBytes }

    private fun recommend(ramGb: Int = biggest.minRamGb + 4, freeGb: Int = 64): HardwareProfile.Recommendation = HardwareProfile.recommendModel(ramGb = ramGb, freeStorageGb = freeGb)

    // ---- defaults -----------------------------------------------------------

    @Test
    fun `defaults to on-device with every action available`() {
        val options = AgentOptions()
        assertThat(options.runtime).isEqualTo(AgentOptions.Runtime.ON_DEVICE)
        assertThat(options.allowedActions)
            .containsExactlyElementsIn(AgentOptions.ActionType.entries)
        assertThat(options.requireConfirmation).isTrue()
    }

    @Test
    fun `defaults follow the hardware recommendation rather than pinning a model`() {
        // A launcher that pins a model on install pins one that may not fit the
        // phone it was installed on.
        assertThat(AgentOptions().modelId).isNull()
        assertThat(AgentOptions().resolveModel(recommend())).isEqualTo(biggest)
    }

    // ---- action gating ------------------------------------------------------

    @Test
    fun `switched-off actions are refused`() {
        val options = AgentOptions(
            allowedActions = setOf(AgentOptions.ActionType.SET_THEME),
        )
        assertThat(options.isAllowed(Action.SetTheme("dark"))).isTrue()
        assertThat(options.isAllowed(Action.HideApp("com.example"))).isFalse()
    }

    @Test
    fun `unknown actions are never allowed even with everything switched on`() {
        val all = AgentOptions(allowedActions = AgentOptions.ActionType.entries.toSet())
        assertThat(all.isAllowed(Action.Unknown)).isFalse()
    }

    @Test
    fun `every parsed action maps to a switch`() {
        // Every wire action ActionExecutor understands must be switchable, or a
        // user cannot turn it off.
        val wireActions = listOf(
            Action.SetTheme("dark"),
            Action.SetWallpaper("#ffffff"),
            Action.CreateFolder("Work", listOf("a", "b")),
            Action.SetAnimationScale(0.5f),
            Action.AddToHome("com.example"),
            Action.HideApp("com.example"),
        )
        for (action in wireActions) {
            val type = AgentOptions.ActionType.of(action)
            assertThat(type).isNotNull()
            assertThat(AgentOptions(allowedActions = setOf(type!!)).isAllowed(action)).isTrue()
        }
        assertThat(wireActions.map { AgentOptions.ActionType.of(it) }.toSet())
            .isEqualTo(AgentOptions.ActionType.entries.toSet())
    }

    @Test
    fun `every action type survives a wire round-trip`() {
        for (type in AgentOptions.ActionType.entries) {
            assertThat(AgentOptions.ActionType.fromWire(type.wireName)).isEqualTo(type)
        }
    }

    // ---- confirmation -------------------------------------------------------

    @Test
    fun `confirmation is required by default and can be waived`() {
        val action = Action.HideApp("com.example")
        assertThat(AgentOptions().needsConfirmation(action)).isTrue()
        assertThat(AgentOptions(requireConfirmation = false).needsConfirmation(action)).isFalse()
    }

    @Test
    fun `a refused action is never prompted for`() {
        // Prompting for something already switched off only trains people to
        // tap through requests they did not ask for.
        val options = AgentOptions(allowedActions = emptySet())
        assertThat(options.needsConfirmation(Action.HideApp("com.example"))).isFalse()
    }

    // ---- model resolution ---------------------------------------------------

    @Test
    fun `pinned model wins over the recommendation`() {
        val options = AgentOptions(modelId = smallest.id)
        assertThat(options.resolveModel(recommend())).isEqualTo(smallest)
    }

    @Test
    fun `a pinned model this phone cannot run falls back to the recommendation`() {
        // Options carried across from a bigger phone must not brick the agent.
        val tinyPhone = HardwareProfile.recommendModel(
            ramGb = smallest.minRamGb,
            freeStorageGb = 64,
        )
        assertThat(tinyPhone.primary).isEqualTo(smallest)

        val options = AgentOptions(modelId = biggest.id)
        assertThat(options.resolveModel(tinyPhone)).isEqualTo(smallest)
    }

    @Test
    fun `an unknown pinned model falls back rather than failing`() {
        val options = AgentOptions(modelId = "no-such-model")
        assertThat(options.resolveModel(recommend())).isEqualTo(biggest)
    }

    @Test
    fun `the cloud runtime has no local model`() {
        val options = AgentOptions(runtime = AgentOptions.Runtime.CLOUD, modelId = smallest.id)
        assertThat(options.resolveModel(recommend())).isNull()
    }

    // ---- validation ---------------------------------------------------------

    @Test
    fun `a sensible default configuration validates clean`() {
        assertThat(AgentOptions().validate(recommend())).isEmpty()
    }

    @Test
    fun `choosing the cloud is surfaced as a caveat`() {
        val issues = AgentOptions(runtime = AgentOptions.Runtime.CLOUD).validate(recommend())
        assertThat(issues).contains(AgentOptions.Issue.CLOUD_TIER)
    }

    @Test
    fun `a phone that cannot run any model is surfaced`() {
        val tinyPhone = HardwareProfile.recommendModel(ramGb = 1, freeStorageGb = 64)
        val issues = AgentOptions().validate(tinyPhone)
        assertThat(issues).contains(AgentOptions.Issue.NO_MODEL_FITS)
    }

    @Test
    fun `an unknown pinned model is surfaced`() {
        val options = AgentOptions(modelId = "no-such-model")
        assertThat(options.validate(recommend())).contains(AgentOptions.Issue.UNKNOWN_MODEL)
    }

    @Test
    fun `a pinned model too big for this phone is surfaced, not silently swapped`() {
        val tinyPhone = HardwareProfile.recommendModel(
            ramGb = smallest.minRamGb,
            freeStorageGb = 64,
        )
        val options = AgentOptions(modelId = biggest.id)
        assertThat(options.validate(tinyPhone)).contains(AgentOptions.Issue.MODEL_TOO_BIG)
    }

    @Test
    fun `a smaller pinned model on a big phone is fine`() {
        val options = AgentOptions(modelId = smallest.id)
        assertThat(options.validate(recommend())).doesNotContain(AgentOptions.Issue.MODEL_TOO_BIG)
    }

    @Test
    fun `switching everything off is surfaced`() {
        val options = AgentOptions(allowedActions = emptySet())
        assertThat(options.validate(recommend()))
            .contains(AgentOptions.Issue.NO_ACTIONS_ALLOWED)
    }

    @Test
    fun `every issue carries a message a person can act on`() {
        for (issue in AgentOptions.Issue.entries) {
            assertThat(issue.message).isNotEmpty()
            assertThat(issue.message.length).isGreaterThan(20)
        }
    }

    // ---- thinking level -----------------------------------------------------

    @Test
    fun `thinking level round-trips over the wire`() {
        for (level in AgentOptions.ThinkingLevel.entries) {
            assertThat(AgentOptions.ThinkingLevel.fromWire(level.wireName)).isEqualTo(level)
        }
    }

    @Test
    fun `an unrecognised thinking level falls back to the default`() {
        // The model can emit anything; an unknown value must not crash setup.
        assertThat(AgentOptions.ThinkingLevel.fromWire("extreme")).isEqualTo(AgentOptions.ThinkingLevel.DEFAULT)
        assertThat(AgentOptions.ThinkingLevel.fromWire(null)).isEqualTo(AgentOptions.ThinkingLevel.DEFAULT)
    }

    // ---- parsed output still respects the gates -----------------------------

    @Test
    fun `gates apply to actions parsed from model output`() {
        val parsed = ActionExecutor.parseAction("""{"action":"hide_app","params":{"package":"x"}}""")
        assertThat(parsed).isInstanceOf(Action.HideApp::class.java)

        val options = AgentOptions(
            allowedActions = setOf(AgentOptions.ActionType.SET_THEME),
        )
        assertThat(options.isAllowed(parsed)).isFalse()
    }
}
