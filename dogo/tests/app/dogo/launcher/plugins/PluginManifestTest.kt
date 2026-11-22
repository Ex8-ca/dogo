/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.plugins

import app.dogo.launcher.agent.Action
import app.dogo.launcher.agent.ActionExecutor
import app.dogo.launcher.agent.AgentOptions
import app.dogo.launcher.privilege.PrivilegePlanner
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.json.JSONObject
import org.junit.Test

class PluginManifestTest {

    private val valid = """
        {
          "schemaVersion": 1,
          "id": "io.github.you.timer",
          "name": "Timer",
          "version": "1.0.0",
          "author": "You",
          "license": "MIT",
          "description": "Timers.",
          "kinds": ["agent-actions"],
          "entryPoints": { "agentActions": ".TimerService" },
          "capabilities": ["notifications"],
          "actions": [{ "name": "start_timer", "description": "Start one.", "params": { "minutes": "number" } }]
        }
    """.trimIndent()

    private fun withField(key: String, value: Any?) = JSONObject(valid).apply { if (value == null) remove(key) else put(key, value) }.toString()

    @Test
    fun `parses a valid manifest`() {
        val m = PluginManifest.parse(valid).getOrThrow()
        assertThat(m.id).isEqualTo("io.github.you.timer")
        assertThat(m.kinds).containsExactly(PluginManifest.Kind.AGENT_ACTIONS)
        assertThat(m.entryPoints[PluginManifest.Kind.AGENT_ACTIONS]).isEqualTo(".TimerService")
        assertThat(m.capabilities).containsExactly(PluginManifest.Capability.NOTIFICATIONS)
        assertThat(m.actions.single().params).containsEntry("minutes", "number")
        assertThat(m.qualifiedAction("start_timer")).isEqualTo("io.github.you.timer:start_timer")
    }

    @Test
    fun `the bundled sample plugin's manifest is valid`() {
        // Keeps the example honest: if the format changes, this breaks.
        val file = listOf("plugins/sample-notes/src/main/assets/dogo-plugin.json", "../plugins/sample-notes/src/main/assets/dogo-plugin.json")
            .map(::File).first { it.exists() }
        val m = PluginManifest.parse(file.readText()).getOrThrow()
        assertThat(m.actions.map { it.name }).containsExactly("save_note")
    }

    @Test
    fun `accepts Omarchy kind names where Android has an equivalent`() {
        assertThat(PluginManifest.Kind.fromWire("bar-widget")).isEqualTo(PluginManifest.Kind.WIDGET)
        assertThat(PluginManifest.Kind.fromWire("panel")).isEqualTo(PluginManifest.Kind.PANEL)
        assertThat(PluginManifest.Kind.fromWire("service")).isEqualTo(PluginManifest.Kind.SERVICE)
        assertThat(PluginManifest.Kind.fromWire("overlay")).isNull()
    }

    @Test
    fun `rejects manifests that would be unsafe or ambiguous`() {
        val cases = mapOf(
            "wrong schema" to withField("schemaVersion", 2),
            "un-namespaced id" to withField("id", "timer"),
            "no license" to withField("license", null),
            "unknown capability" to withField("capabilities", org.json.JSONArray(listOf("everything"))),
            "unknown kind" to withField("kinds", org.json.JSONArray(listOf("rootkit"))),
            "missing entry point" to withField("entryPoints", JSONObject()),
            "actions plugin without actions" to withField("actions", org.json.JSONArray()),
            "bad action name" to withField(
                "actions",
                org.json.JSONArray().put(JSONObject().put("name", "Start Timer")),
            ),
            "not json" to "{nope",
        )
        cases.forEach { (case, json) ->
            assertWithMessage(case, PluginManifest.parse(json).isFailure)
        }
    }

    private fun assertWithMessage(case: String, failed: Boolean) {
        com.google.common.truth.Truth.assertWithMessage(case).that(failed).isTrue()
    }

    @Test
    fun `entry points resolve like Android manifest names`() {
        assertThat(PluginRegistry.qualify("io.x", ".Svc")).isEqualTo("io.x.Svc")
        assertThat(PluginRegistry.qualify("io.x", "Svc")).isEqualTo("io.x.Svc")
        assertThat(PluginRegistry.qualify("io.x", "com.other.Svc")).isEqualTo("com.other.Svc")
    }

    // ---- actions ----------------------------------------------------------

    @Test
    fun `namespaced actions parse as plugin actions and round trip`() {
        val json = """{"action": "io.github.you.timer:start_timer", "params": {"minutes": 5}}"""
        val action = ActionExecutor.parseAction(json)
        assertThat(action).isEqualTo(Action.Plugin("io.github.you.timer", "start_timer", """{"minutes":5}"""))
        assertThat(ActionExecutor.parseAction(ActionExecutor.toJson(action))).isEqualTo(action)
    }

    @Test
    fun `malformed plugin action names are unknown`() {
        listOf("timer:start", "io.github.you.timer:", "io.github.you.timer:Start", ":start_timer").forEach { name ->
            assertThat(ActionExecutor.parseAction("""{"action": "$name", "params": {}}""")).isEqualTo(Action.Unknown)
        }
    }

    @Test
    fun `plugin actions run only for enabled plugins and obey the gate`() {
        val action = Action.Plugin("io.github.you.timer", "start_timer", "{}")
        val off = PrivilegePlanner.plan(action, PrivilegePlanner.Availability(), "app.dogo")
        assertThat(off).isInstanceOf(PrivilegePlanner.Plan.Blocked::class.java)
        val on = PrivilegePlanner.plan(action, PrivilegePlanner.Availability(enabledPlugins = setOf("io.github.you.timer")), "app.dogo")
        assertThat(on).isEqualTo(PrivilegePlanner.Plan.ViaPlugin("io.github.you.timer"))

        assertThat(AgentOptions().isAllowed(action)).isTrue()
        val noPlugins = AgentOptions(allowedActions = AgentOptions.ActionType.entries.toSet() - AgentOptions.ActionType.PLUGIN)
        assertThat(noPlugins.isAllowed(action)).isFalse()
    }
}
