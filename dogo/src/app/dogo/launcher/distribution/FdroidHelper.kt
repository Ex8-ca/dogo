package app.dogo.launcher.distribution

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Points the user at F-Droid, which is the recommended way to install apps on
 * a Dogo-configured phone.
 */
object FdroidHelper {

    /** The F-Droid client / repository app. */
    const val FDROID_CLIENT_PACKAGE = "org.fdroid.client"

    /** Kept for the F-Droid classic package, which some builds still use. */
    const val FDROID_PACKAGE = "org.fdroid.fdroid"

    /** Obtanium, for users who would rather track releases from source. */
    const val OBTAINIUM_PACKAGE = "dev.imranr.obtainium"

    const val FDROID_DOWNLOAD_URL = "https://f-droid.org/F-Droid.apk"

    /** Package names that can satisfy an app request. */
    val STORE_PACKAGES = listOf(FDROID_CLIENT_PACKAGE, FDROID_PACKAGE, OBTAINIUM_PACKAGE)

    fun isInstalled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: Exception) {
        false
    }

    fun isInstalled(context: Context): Boolean = STORE_PACKAGES.any { isInstalled(context, it) }

    /** The launch intent of the first installed store, if there is one. */
    fun launchIntentForInstalledStore(context: Context): Intent? = STORE_PACKAGES
        .firstOrNull { isInstalled(context, it) }
        ?.let { context.packageManager.getLaunchIntentForPackage(it) }

    /** Open the installed store, or fall back to the download page. */
    fun openOrDownload(context: Context) {
        val intent = launchIntentForInstalledStore(context)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse(FDROID_DOWNLOAD_URL))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
