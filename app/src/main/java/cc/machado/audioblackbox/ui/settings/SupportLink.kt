package cc.machado.audioblackbox.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import cc.machado.audioblackbox.R

/**
 * Issue #444: the single source of truth for the "Support the project" link in Settings.
 * Owner-verified to exist on 2026-10-15. Every other piece of the support flow reads this constant;
 * there is no second copy of the URL anywhere in the app.
 */
const val SUPPORT_URL = "https://github.com/sponsors/alexandre-machado"

/**
 * What the support row launches, as plain strings so the JVM unit tier can assert it without an
 * Android runtime (`Intent`/`Uri` are stubs on the host JVM; `Intent.ACTION_VIEW` is a compile-time
 * constant and is inlined, so it is safe to read here).
 */
data class SupportLinkTarget(val action: String, val url: String)

fun supportLinkTarget(): SupportLinkTarget = SupportLinkTarget(Intent.ACTION_VIEW, SUPPORT_URL)

/** The real Intent built from [supportLinkTarget]: opens the sponsor page in the system browser. */
fun supportIntent(): Intent = supportLinkTarget().let { target ->
    Intent(target.action, target.url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
}

/**
 * Launches [supportIntent] from [context]. When no app resolves it (no browser installed or all of
 * them disabled), shows a visible toast instead of crashing. Returns whether the launch happened.
 */
fun launchSupportLink(context: Context): Boolean {
    val intent = supportIntent()
    if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.settings_support_no_browser), Toast.LENGTH_LONG).show()
        false
    }
}
