package cc.machado.audioblackbox.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Wraps the battery-optimization exemption flow, which cannot be granted in-app and always
 * routes through system Settings: the direct per-app ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 * prompt (fast path, works on most OEMs), falling back to the general
 * ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS list if no activity on this device can handle the
 * direct intent (some OEM skins restrict or omit it).
 */
object BatteryOptimization {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** The direct per-app exemption prompt. Callers should resolve it before starting it. */
    fun requestExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /** The general battery-optimization settings list, used when the direct intent has no handler. */
    fun settingsFallbackIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /**
     * The intent to actually start: the direct per-app prompt if a component can handle it on
     * this device, else the general settings list fallback.
     */
    fun bestAvailableIntent(context: Context): Intent {
        val direct = requestExemptionIntent(context)
        val canHandleDirect = direct.resolveActivity(context.packageManager) != null
        return if (canHandleDirect) direct else settingsFallbackIntent()
    }
}
