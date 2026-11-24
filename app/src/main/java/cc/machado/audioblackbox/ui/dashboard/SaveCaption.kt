package cc.machado.audioblackbox.ui.dashboard

import androidx.annotation.StringRes
import cc.machado.audioblackbox.R

/** The in-flight lookback export owns the Save caption even if Live starts meanwhile. */
@StringRes
internal fun activeSaveCaptionResource(isExporting: Boolean, isSavingToLive: Boolean): Int? = when {
    isExporting -> R.string.dashboard_panel_save_caption_saving
    isSavingToLive -> R.string.dashboard_panel_save_caption_live
    else -> null
}
