package com.unscathed.monitor.roblox

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.unscathed.monitor.accessibility.BlockingOverlay
import com.unscathed.monitor.accessibility.WatchdogAccessibilityService
import com.unscathed.monitor.analyzer.NormBox
import com.unscathed.monitor.capture.realDisplayMetrics
import kotlinx.coroutines.delay
import timber.log.Timber

class RobloxController(private val context: Context) {

    fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * The app whose window will receive taps instead of the game, if anything is in the way.
     *
     * Worth checking *before* spending a tap attempt: a swallowed tap looks exactly like a tap
     * that landed and did nothing, so without this the automation would burn all its attempts
     * and stand down without ever saying why.
     */
    fun tapBlocker(gamePackage: String): BlockingOverlay? {
        val service = WatchdogAccessibilityService.instance ?: return null
        return service.blockingOverlay(allowedPackages = setOf(gamePackage, context.packageName))
    }

    /** Taps the center of an OCR box (normalized coordinates) on the real screen. */
    suspend fun tap(box: NormBox): Boolean {
        val service = WatchdogAccessibilityService.instance ?: return false
        val metrics = realDisplayMetrics(context)
        return service.tap(box.centerX * metrics.widthPixels, box.centerY * metrics.heightPixels)
    }

    /**
     * Best-effort restart: dismiss an ANR dialog, go Home, kill Roblox if the OS still lets us
     * (below Android 14), then open the first game link Roblox accepts, falling back to the
     * plain launcher intent.
     */
    suspend fun relaunch(pkg: String, launchUris: List<String>): Boolean {
        val service = WatchdogAccessibilityService.instance
        if (service == null) {
            Timber.w("Relaunch without accessibility; Android may block the background launch")
        }

        if (service?.dismissAnrDialog() == true) delay(1_500)
        service?.goHome()
        delay(2_000)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { context.getSystemService(ActivityManager::class.java).killBackgroundProcesses(pkg) }
            delay(1_500)
        }

        val intents = buildList {
            launchUris.forEach { add(Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(pkg)) }
            context.packageManager.getLaunchIntentForPackage(pkg)?.let(::add)
        }.map { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }

        for (intent in intents) {
            val started = if (service != null) {
                service.launch(intent)
            } else {
                try {
                    context.startActivity(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    false
                } catch (e: SecurityException) {
                    false
                }
            }
            if (started) return true
        }
        return false
    }
}
