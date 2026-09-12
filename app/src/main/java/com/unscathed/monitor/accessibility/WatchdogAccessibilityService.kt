package com.unscathed.monitor.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * Used for a few narrow things: seeing which app is on screen, tapping a point (Roblox draws
 * its own UI, so there are no nodes to click, only coordinates from OCR), pressing Home or the
 * system "Close app" button when relaunching, and flipping the Wi-Fi panel switch when the
 * user enabled that. Being bound by the system also lets us start Roblox from the background.
 */
/** A window covering the game that taps will hit instead. [layer] is its z-order. */
data class BlockingOverlay(val packageName: String, val layer: Int, val coversFraction: Float) {
    val coversPercent: Int get() = (coversFraction * 100).toInt()
}

class WatchdogAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Timber.i("Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Packages that own a visible application window right now, or null if unreadable. */
    fun visibleAppPackages(): Set<String>? = try {
        val ws = windows
        if (ws.isEmpty()) {
            null
        } else {
            ws.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root?.packageName?.toString() }
                .toSet()
        }
    } catch (e: Exception) {
        Timber.w(e, "Could not read windows")
        null
    }

    /**
     * A window sitting on top of the game that will swallow taps, if there is one.
     *
     * Accessibility gestures are injected at the display level, so they land on whichever
     * touchable window is topmost at that point - not necessarily the game. A full-screen
     * touch-lock or dimmer overlay therefore eats every tap the watchdog sends, silently.
     * Android offers no way to tap past it (by design), so the only useful thing to do is
     * notice and say which app is responsible.
     */
    fun blockingOverlay(allowedPackages: Set<String>): BlockingOverlay? = try {
        val metrics = resources.displayMetrics
        val screenArea = metrics.widthPixels.toLong() * metrics.heightPixels
        if (screenArea <= 0) {
            null
        } else {
            val bounds = Rect()
            windows.asSequence()
                // Overlays drawn over other apps report as TYPE_SYSTEM; app windows as TYPE_APPLICATION.
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
                .mapNotNull { window ->
                    val pkg = window.root?.packageName?.toString()
                    if (pkg != null && pkg in allowedPackages) return@mapNotNull null
                    window.getBoundsInScreen(bounds)
                    val fraction = (bounds.width().toLong() * bounds.height()) / screenArea.toFloat()
                    // Status and navigation bars are always on top and always small; ignore them.
                    if (fraction < MIN_BLOCKING_FRACTION) null
                    else BlockingOverlay(pkg ?: "an unidentified app", window.layer, fraction)
                }
                .maxByOrNull { it.layer }
        }
    } catch (e: Exception) {
        Timber.w(e, "Could not read windows")
        null
    }

    suspend fun tap(x: Float, y: Float): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
                .build()
            val dispatched = dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!dispatched && cont.isActive) cont.resume(false)
        }
    }

    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /**
     * Clicks the system "App isn't responding" dialog's close button if it is showing.
     * Only looks at windows owned by the `android` package so it can't click anything else.
     */
    fun dismissAnrDialog(): Boolean {
        val labels = setOf("close app", "close")
        for (window in windows) {
            val root = window.root ?: continue
            if (root.packageName?.toString() != "android") continue
            val node = findFirst(root) { n ->
                val text = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase()
                text != null && text in labels
            } ?: continue
            if (click(node)) return true
        }
        return false
    }

    /**
     * The on/off switch of the system Wi-Fi panel ([android.provider.Settings.Panel.ACTION_WIFI]).
     * Only searches windows owned by a settings package, where the Wi-Fi toggle is the only switch.
     */
    fun findSettingsToggle(): AccessibilityNodeInfo? {
        for (window in windows) {
            val root = window.root ?: continue
            val pkg = root.packageName?.toString() ?: continue
            if (!pkg.contains("settings", ignoreCase = true)) continue
            findFirst(root) { it.isCheckable && it.isEnabled }?.let { return it }
        }
        return null
    }

    fun click(node: AccessibilityNodeInfo): Boolean =
        generateSequence(node) { it.parent }.firstOrNull { it.isClickable }
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun launch(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        Timber.e(e, "Launch from accessibility context failed")
        false
    }

    private fun findFirst(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return null
    }

    companion object {
        @Volatile
        var instance: WatchdogAccessibilityService? = null
            private set

        private const val TAP_DURATION_MS = 80L
        private const val MAX_NODES = 500
        /** A window has to cover this much of the screen before it counts as blocking taps. */
        private const val MIN_BLOCKING_FRACTION = 0.5f
    }
}
