package com.unscathed.monitor.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

data class BatteryInfo(
    val percent: Int,
    val charging: Boolean,
    val source: String,
    val temperatureC: Float,
) {
    val summary: String get() = "$percent% • " + if (charging) "Charging ($source)" else "On battery"
    val temperatureLabel: String get() = "%.1f°C".format(temperatureC)
}

class BatteryMonitor(private val context: Context) {
    /** Reads the sticky ACTION_BATTERY_CHANGED broadcast; cheap enough to call every tick. */
    fun read(): BatteryInfo? {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val tenthsC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)

        val charging = plugged != 0 ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> if (charging) "Charger" else "None"
        }
        return BatteryInfo(
            percent = if (level < 0) -1 else level * 100 / scale,
            charging = charging,
            source = source,
            temperatureC = tenthsC / 10f,
        )
    }
}
