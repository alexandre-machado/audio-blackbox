package cc.machado.audioblackbox.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Stock Material 3 theme (issue #9's decision, bound in `.agents/team.toml`): no custom design
 * language, no Material 3 Expressive. Colors are the library's own default light/dark schemes,
 * replaced by the platform's dynamic (wallpaper-derived) scheme when available (Android 12+ /
 * API 31+) so the app looks native on devices that support it, per issue #6's "respects dynamic
 * color where available" criterion. Typography/shapes are left at [MaterialTheme]'s own defaults
 * for the same "native-Android look, not themed" reason -- nothing here overrides them.
 */
@Composable
fun AudioBlackboxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
