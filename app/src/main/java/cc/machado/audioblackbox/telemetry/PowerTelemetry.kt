package cc.machado.audioblackbox.telemetry

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

data class BatteryStatus(
    val percent: Int = 100,
    val isCharging: Boolean = false,
    val isIgnoringOptimizations: Boolean = true,
)

object PowerTelemetry {
    fun getBatteryStatus(context: Context): BatteryStatus {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

            val percent = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 }
                ?: run {
                    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                    if (level >= 0 && scale > 0) ((level.toFloat() / scale.toFloat()) * 100f).toInt() else 100
                }

            val isCharging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val status = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
                    ?: BatteryManager.BATTERY_STATUS_UNKNOWN
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            } else {
                val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            }

            val isIgnoringOptimizations = powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true

            BatteryStatus(
                percent = percent,
                isCharging = isCharging,
                isIgnoringOptimizations = isIgnoringOptimizations,
            )
        } catch (_: Throwable) {
            BatteryStatus(percent = 100, isCharging = false, isIgnoringOptimizations = true)
        }
    }

    /**
     * `ActivityManager.MemoryInfo.availMem` (issue #298): the system-wide free-memory reading
     * [cc.machado.audioblackbox.audio.DeviceMemoryBudget.maxRetentionMinutes]'s
     * `availableSystemBytes` parameter is meant to receive -- a heap ceiling means nothing if the
     * system as a whole is out of memory. `null` on any failure to read it (a `SecurityException`
     * or missing service is not expected, but this value only ever *tightens* an already-computed
     * heap-based ceiling -- see that function's doc -- so failing open to "unknown, don't apply
     * this second limit" is the safe default, never a crash).
     */
    fun getAvailableSystemBytes(context: Context): Long? = try {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(info)
        activityManager?.let { info.availMem }
    } catch (_: Throwable) {
        null
    }
}
