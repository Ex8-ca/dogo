/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.plugins

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Runs a plugin action the user approved.
 *
 * The contract is a bound service that speaks [Messenger], so a plugin needs
 * no Dogo library and no AIDL. (Binding, unlike startService, is allowed while
 * the plugin app is in the background.) Dogo binds to the plugin's
 * agentActions entry point with [PluginManifest.ACTION_PLUGIN] and sends one
 * [Message] with `what` = [MSG_RUN_ACTION], data [KEY_ACTION] and [KEY_PARAMS]
 * (JSON), and `replyTo` set. The plugin replies with `what` = [RESULT_OK] or
 * [RESULT_ERROR] and a [KEY_MESSAGE] string for the chat.
 */
class PluginActionRunner(context: Context) {
    private val context = context.applicationContext

    suspend fun run(plugin: PluginRegistry.Installed, action: String, params: JSONObject): Result<String> {
        val entry = plugin.manifest.entryPoints[PluginManifest.Kind.AGENT_ACTIONS]
            ?: return Result.failure(IllegalStateException("${plugin.manifest.name} has no agent actions."))
        if (plugin.manifest.actions.none { it.name == action }) {
            return Result.failure(IllegalArgumentException("${plugin.manifest.name} doesn't offer \"$action\"."))
        }
        val name = plugin.manifest.name
        val reply = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                var connection: ServiceConnection? = null
                fun finish(result: Result<String>) {
                    connection?.let { runCatching { context.unbindService(it) } }
                    connection = null
                    if (cont.isActive) cont.resume(result)
                }
                val replies = Messenger(
                    Handler(Looper.getMainLooper()) { msg ->
                        val text = msg.data?.getString(KEY_MESSAGE).orEmpty()
                        finish(
                            if (msg.what == RESULT_OK) {
                                Result.success(text.ifBlank { "Done." })
                            } else {
                                Result.failure(IllegalStateException(text.ifBlank { "$name couldn't do that." }))
                            },
                        )
                        true
                    },
                )
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(component: ComponentName, binder: IBinder) {
                        val request = Message.obtain(null, MSG_RUN_ACTION).apply {
                            data = Bundle().apply {
                                putString(KEY_ACTION, action)
                                putString(KEY_PARAMS, params.toString())
                            }
                            replyTo = replies
                        }
                        runCatching { Messenger(binder).send(request) }
                            .onFailure { finish(Result.failure(IllegalStateException("$name stopped responding."))) }
                    }

                    override fun onServiceDisconnected(component: ComponentName) {
                        finish(Result.failure(IllegalStateException("$name stopped while working.")))
                    }

                    override fun onNullBinding(component: ComponentName) {
                        finish(Result.failure(IllegalStateException("$name isn't a bound-service plugin (onBind returned null).")))
                    }
                }
                connection = conn
                val intent = Intent(PluginManifest.ACTION_PLUGIN)
                    .setComponent(ComponentName(plugin.packageName, PluginRegistry.qualify(plugin.packageName, entry)))
                val bound = runCatching { context.bindService(intent, conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
                if (!bound) finish(Result.failure(IllegalStateException("Couldn't connect to $name.")))
                cont.invokeOnCancellation { connection?.let { c -> runCatching { context.unbindService(c) } } }
            }
        }
        return reply ?: Result.failure(IllegalStateException("$name didn't answer within ${TIMEOUT_MS / 1000}s."))
    }

    companion object {
        const val MSG_RUN_ACTION = 1
        const val KEY_ACTION = "action"
        const val KEY_PARAMS = "params"
        const val KEY_MESSAGE = "message"
        const val RESULT_OK = 0
        const val RESULT_ERROR = 1
        private const val TIMEOUT_MS = 15_000L
    }
}
