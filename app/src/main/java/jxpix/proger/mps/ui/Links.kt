package jxpix.proger.mps.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import jxpix.proger.mps.BuildConfig

/** The pages of the app's website that the app sends people to. */
object Links {
    /** Empty when the app was built without a website address. */
    val site: String get() = BuildConfig.SITE_URL.trim().trimEnd('/')
    val available: Boolean get() = site.isNotEmpty()

    private fun language(context: Context): String =
        if (context.resources.configuration.locales[0].language == "ru") "ru" else "en"

    /** Every page of the site is a folder, so its address ends with a slash: no redirect on the way. */
    private fun page(context: Context, name: String): String =
        site + (if (language(context) == "ru") "/" else "/en/") + (if (name.isEmpty()) "" else "$name/")

    fun terms(context: Context): String = page(context, "terms")
    fun privacy(context: Context): String = page(context, "privacy")
    fun home(context: Context): String = page(context, "")

    /** The feedback form, told which version on which phone the person is writing from. */
    fun feedback(context: Context): String = Uri.parse(page(context, "feedback")).buildUpon()
        .appendQueryParameter("src", "app")
        .appendQueryParameter("v", BuildConfig.VERSION_NAME)
        .appendQueryParameter("android", Build.VERSION.RELEASE ?: "")
        .appendQueryParameter("device", ((Build.MANUFACTURER ?: "") + " " + (Build.MODEL ?: "")).trim())
        .build().toString()

    /** Opens the page in the browser; false when the phone has nothing to open it with. */
    fun open(context: Context, url: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }
}
