package com.unscathed.monitor.system

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.unscathed.monitor.accessibility.WatchdogAccessibilityService

/**
 * Answers "is Roblox on screen?". Uses the accessibility service when it's enabled (instant
 * and exact), otherwise falls back to UsageStats (needs Usage Access). Returns null when
 * neither is available, or when the watchdog's own UI is in front (you're checking the
 * dashboard, so that shouldn't count as Roblox closing).
 */
class ForegroundAppDetector(private val context: Context) {
    enum class Source(val label: String) {
        ACCESSIBILITY("Accessibility"),
        USAGE_STATS("Usage access"),
        NONE("Unavailable"),
    }

    private val usm = context.getSystemService(UsageStatsManager::class.java)
    private var lastQueryEndMs = 0L
    private var lastResumedPackage: String? = null

    val source: Source
        get() = when {
            WatchdogAccessibilityService.instance != null -> Source.ACCESSIBILITY
            hasUsageAccess(context) -> Source.USAGE_STATS
            else -> Source.NONE
        }

    fun isForeground(pkg: String): Boolean? {
        WatchdogAccessibilityService.instance?.visibleAppPackages()?.let { visible ->
            return when {
                pkg in visible -> true
                context.packageName in visible -> null
                else -> false
            }
        }
        if (!hasUsageAccess(context)) return null
        return when (val top = queryTopPackage()) {
            null -> null
            pkg -> true
            context.packageName -> null
            else -> false
        }
    }

    /** Reads usage events incrementally since the last call and keeps the last resumed package. */
    private fun queryTopPackage(): String? {
        val now = System.currentTimeMillis()
        val begin = if (lastQueryEndMs == 0L) now - 24 * 60 * 60 * 1000L else lastQueryEndMs - 1000
        val events = usm.queryEvents(begin, now) ?: return lastResumedPackage
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // ACTIVITY_RESUMED (API 29+) shares its value with the older MOVE_TO_FOREGROUND.
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) lastResumedPackage = event.packageName
        }
        lastQueryEndMs = now
        return lastResumedPackage
    }

    companion object {
        fun hasUsageAccess(context: Context): Boolean {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
