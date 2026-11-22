/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.agent.backend

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A command-line agent installed in Termux, such as Claude Code, OpenCode or
 * Codex: the phone version of Omarchy's "pick your agent CLI" menu.
 *
 * The conversation is rendered into one prompt, passed on stdin to the
 * agent's non-interactive mode (e.g. `claude -p`), and stdout is the reply.
 * The agent runs as Termux's user, inside Termux's sandbox, not Dogo's.
 *
 * Needs Termux's RUN_COMMAND permission (Dogo asks for it) and
 * `allow-external-apps = true` in ~/.termux/termux.properties.
 */
class TermuxCliBackend(
    context: Context,
    private val cli: TermuxCli,
) : AgentBackend {
    private val context = context.applicationContext

    override val label: String = "${cli.displayName} (Termux)"

    override suspend fun reply(system: String, turns: List<ChatTurn>, options: ReplyOptions): Result<String> {
        val prompt = renderPrompt(system, turns)
        val result = Termux.run(context, cli.argv, stdin = prompt, timeoutMs = cli.timeoutMs)
            .getOrElse { return Result.failure(it) }
        return when {
            result.exitCode != 0 -> Result.failure(
                AgentBackendException(
                    "${cli.displayName} exited with code ${result.exitCode}: " +
                        result.stderr.ifBlank { result.stdout }.trim().take(300).ifBlank { "no output" },
                ),
            )

            result.stdout.isBlank() -> Result.failure(AgentBackendException("${cli.displayName} printed nothing."))

            else -> Result.success(result.stdout.trim())
        }
    }

    companion object {
        /** One flat prompt: CLI agents take a single message, not chat roles. */
        fun renderPrompt(system: String, turns: List<ChatTurn>): String = buildString {
            append(system)
            append("\n\nReply as the assistant to the last user message only. ")
            append("Do not run tools or edit files; just answer.\n\n")
            turns.forEach { turn ->
                append(if (turn.role == ChatTurn.Role.USER) "User: " else "Assistant: ")
                append(turn.text)
                append("\n")
            }
            append("Assistant:")
        }
    }
}

/** A CLI agent Dogo knows how to run non-interactively. */
data class TermuxCli(
    val id: String,
    val displayName: String,
    /** argv; argv[0] is resolved inside Termux's bin directory. */
    val argv: List<String>,
    val installHint: String,
    val timeoutMs: Long = 180_000,
) {
    companion object {
        val CLAUDE = TermuxCli(
            "claude",
            "Claude Code",
            listOf("claude", "-p", "--output-format", "text"),
            "pkg install nodejs && npm i -g @anthropic-ai/claude-code, then run claude once to sign in",
        )
        val OPENCODE = TermuxCli(
            "opencode",
            "OpenCode",
            listOf("opencode", "run"),
            "npm i -g opencode-ai, then run opencode once to sign in",
        )
        val CODEX = TermuxCli(
            "codex",
            "Codex",
            listOf("codex", "exec", "-"),
            "npm i -g @openai/codex, then run codex once to sign in",
        )
        val HERMES = TermuxCli(
            "hermes",
            "Hermes Agent",
            listOf("hermes", "chat", "-q", "-"),
            "install Hermes Agent in Termux (see hermes-agent.nousresearch.com)",
        )

        val all = listOf(CLAUDE, OPENCODE, CODEX, HERMES)

        fun find(id: String?): TermuxCli? = all.firstOrNull { it.id == id }
    }
}

/** Termux's RUN_COMMAND API. */
object Termux {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val BIN = "/data/data/com.termux/files/usr/bin"
    private const val SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION = "com.termux.RUN_COMMAND"
    private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    private const val RESULT_BUNDLE = "result"

    data class RunResult(val stdout: String, val stderr: String, val exitCode: Int)

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, PackageManager.PackageInfoFlags.of(0))
    }.isSuccess

    fun hasPermission(context: Context): Boolean = ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Runs `argv` in Termux, in the background, and waits for the result.
     * argv[0] is looked up in Termux's bin directory via `/usr/bin/env`, so a
     * command installed with npm or pip is found the same way the shell would.
     */
    suspend fun run(context: Context, argv: List<String>, stdin: String? = null, timeoutMs: Long = 60_000): Result<RunResult> {
        if (!isInstalled(context)) return Result.failure(AgentBackendException("Termux isn't installed. Get it from F-Droid."))
        if (!hasPermission(context)) {
            return Result.failure(
                AgentBackendException("Dogo needs permission to run Termux commands. Allow it in Dogo Options."),
            )
        }
        val action = "app.dogo.launcher.TERMUX_RESULT." + UUID.randomUUID()
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        runCatching { context.unregisterReceiver(this) }
                        val bundle: Bundle? = intent.getBundleExtra(RESULT_BUNDLE)
                        val err = bundle?.getInt("err", -1) ?: -1
                        val outcome = when {
                            bundle == null -> Result.failure(AgentBackendException("Termux sent back no result."))

                            err > 0 -> Result.failure(
                                AgentBackendException(
                                    "Termux couldn't run it: " + (bundle.getString("errmsg") ?: "error $err").trim() +
                                        ". Check allow-external-apps = true in ~/.termux/termux.properties.",
                                ),
                            )

                            else -> Result.success(
                                RunResult(
                                    stdout = bundle.getString("stdout").orEmpty(),
                                    stderr = bundle.getString("stderr").orEmpty(),
                                    exitCode = bundle.getInt("exitCode", -1),
                                ),
                            )
                        }
                        if (cont.isActive) cont.resume(outcome)
                    }
                }
                ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_EXPORTED)
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }

                val pending = PendingIntent.getBroadcast(
                    context,
                    action.hashCode(),
                    Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
                )
                val intent = Intent(ACTION)
                    .setComponent(ComponentName(PACKAGE, SERVICE))
                    .putExtra(EXTRA_PATH, "$BIN/env")
                    .putExtra(EXTRA_ARGUMENTS, argv.toTypedArray())
                    .putExtra(EXTRA_BACKGROUND, true)
                    .putExtra(EXTRA_LABEL, "Dogo: ${argv.first()}")
                    .putExtra(EXTRA_PENDING_INTENT, pending)
                    .apply { if (stdin != null) putExtra(EXTRA_STDIN, stdin) }
                val started = runCatching { context.startForegroundService(intent) }
                if (started.isFailure && cont.isActive) {
                    runCatching { context.unregisterReceiver(receiver) }
                    cont.resume(Result.failure(AgentBackendException("Couldn't reach Termux: ${started.exceptionOrNull()?.message}")))
                }
            }
        }
        return result ?: Result.failure(AgentBackendException("${argv.first()} in Termux didn't finish within ${timeoutMs / 1000}s."))
    }
}
