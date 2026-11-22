/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.privilege

import android.content.pm.PackageManager
import com.topjohnwu.superuser.Shell
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/** Quotes one argv element for `sh -c`. */
internal fun shellQuote(arg: String): String = if (arg.isNotEmpty() && arg.all { it.isLetterOrDigit() || it in "._-/:=@%+," }) {
    arg
} else {
    "'" + arg.replace("'", "'\\''") + "'"
}

internal fun List<String>.toShellLine(): String = joinToString(" ") { shellQuote(it) }

/**
 * Root through libsu (already used by Lawnchair for QuickSwitch).
 *
 * [isReady] never triggers the root manager's prompt: it only reports a grant
 * that already exists. The prompt is shown by [requestAccess], when the user
 * asks for it from the access screen.
 */
class RootShellRunner : ShellRunner {
    override val tier = Tier.ROOT

    override suspend fun isReady(): Boolean = withContext(Dispatchers.IO) {
        Shell.isAppGrantedRoot() == true
    }

    /** Opens a root shell, which makes Magisk/KernelSU ask the user. */
    suspend fun requestAccess(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Shell.getShell().isRoot }.getOrDefault(false)
    }

    override suspend fun run(command: List<String>): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val result = Shell.cmd(command.toShellLine()).exec()
            if (!result.isSuccess) {
                error((result.err + result.out).joinToString("\n").ifBlank { "exit code ${result.code}" })
            }
            result.out.joinToString("\n")
        }
    }
}

/**
 * Shizuku: adb-level shell access without root.
 *
 * The Shizuku app must be installed and started (after each reboot, unless
 * the phone is rooted), and Dogo must be granted access in it once.
 */
class ShizukuShellRunner : ShellRunner {
    override val tier = Tier.SHIZUKU

    /** The Shizuku service is running and reachable. */
    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = isRunning() &&
        !Shizuku.isPreV11() &&
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    override suspend fun isReady(): Boolean = hasPermission()

    /** Shows Shizuku's "Allow Dogo?" dialog and waits for the answer. */
    suspend fun requestAccess(): Boolean {
        if (!isRunning() || Shizuku.isPreV11()) return false
        if (hasPermission()) return true
        return withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine { cont ->
                val listener = object : Shizuku.OnRequestPermissionResultListener {
                    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                        if (requestCode != REQUEST_CODE) return
                        Shizuku.removeRequestPermissionResultListener(this)
                        if (cont.isActive) cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                    }
                }
                Shizuku.addRequestPermissionResultListener(listener)
                cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } ?: false
    }

    override suspend fun run(command: List<String>): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            check(hasPermission()) { "Shizuku isn't running or hasn't granted Dogo access." }
            val process = newProcess(command.toTypedArray())
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code != 0) error(err.ifBlank { out }.ifBlank { "exit code $code" }.trim())
            out.trim()
        }
    }

    /**
     * Shizuku 13 keeps newProcess private and steers apps to UserService,
     * which needs an AIDL service of its own. For a handful of one-shot
     * commands that is a lot of machinery; the method still exists and is
     * stable across 13.x, so call it directly.
     */
    private fun newProcess(argv: Array<String>): Process {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, argv, null, null) as Process
    }

    companion object {
        const val PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 4242
    }
}
