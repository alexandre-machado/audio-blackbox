package cc.machado.audioblackbox.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the system is currently willing to play animations, read from
 * [Settings.Global.ANIMATOR_DURATION_SCALE].
 *
 * Users turn animation scale to zero for real reasons -- vestibular disorders, or simply an old
 * phone -- and the platform expects apps to honour it rather than assuming it only affects
 * framework transitions. Introduced with issue
 * [#284](https://github.com/alexandre-machado/audio-blackbox/issues/284); nothing in this
 * repository read the setting before.
 *
 * Callers must degrade to the *end state* of the animation, never to nothing: with animations off,
 * the REMOVE BEFORE FLIGHT tag still reappears and still comes off when pulled, it just does so
 * without the entry slide or the spring.
 *
 * Read once when the composable enters composition (the setting is a system-wide preference that
 * changes about as often as a user visits developer options, and a live `ContentObserver` for it
 * would be more machinery than the one animation here justifies).
 */
@Composable
fun rememberSystemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) != 0f
    }
}
