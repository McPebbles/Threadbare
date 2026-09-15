package com.threadbare.client.web

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.threadbare.client.util.Safely

/**
 * Handing a link to a browser, ordinarily or privately.
 *
 * The honesty rule from [PrivateTabs] is enforced here: [openPrivately] returns
 * false rather than falling back to an ordinary tab, so the caller has to decide
 * what to tell the user instead of a private launch silently becoming a normal
 * one.
 */
object BrowserLauncher {

    /** The package that would handle an ordinary https link, or null. */
    fun defaultBrowser(context: Context): String? = Safely.call({
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/"))
        val resolved = context.packageManager.resolveActivity(
            probe, PackageManager.MATCH_DEFAULT_ONLY,
        )
        resolved?.activityInfo?.packageName?.takeIf { it != "android" }
    }, null)

    /** Which of the private-capable browsers are actually installed. */
    fun installedPrivateBrowsers(context: Context): List<PrivateTabs.Recipe> {
        val pm = context.packageManager
        return PrivateTabs.recipes().filter { recipe ->
            Safely.call({
                pm.getLaunchIntentForPackage(recipe.packageName) != null
            }, false)
        }
    }

    fun privateOption(context: Context): PrivateTabs.Recipe? =
        PrivateTabs.choose(
            installedPrivateBrowsers(context).map { it.packageName },
            defaultBrowser(context),
        )

    /** An ordinary external open. Returns false when nothing could handle it. */
    fun openNormally(context: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        return Safely.call({ context.startActivity(intent); true }, false)
    }

    /**
     * Open in a private tab, or report that it could not be done.
     *
     * **Never falls back to an ordinary tab.** A user who asked for private
     * browsing and got a normal tab is worse off than one who was told it was
     * unavailable, because they will read as though they are protected. The
     * caller decides what to do with a false.
     */
    fun openPrivately(context: Context, url: String, recipe: PrivateTabs.Recipe): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            setPackage(recipe.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addCategory(Intent.CATEGORY_BROWSABLE)
            if (recipe.basis == PrivateTabs.Basis.EXTRA) {
                putExtra(PrivateTabs.EXTRA_PRIVATE, true)
            }
        }
        return Safely.call({ context.startActivity(intent); true }, false)
    }

    /** The always-available escape hatch when no browser will oblige. */
    fun copyToClipboard(context: Context, url: String): Boolean = Safely.call({
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("link", url))
        true
    }, false)
}
