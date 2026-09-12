package com.unscathed.monitor.analyzer

import com.unscathed.monitor.capture.CapturedFrame
import com.unscathed.monitor.config.ActiveGame
import com.unscathed.monitor.config.WatchdogSettings
import com.unscathed.monitor.games.Feature
import com.unscathed.monitor.state.DisconnectCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Everything one tick learned about the screen. */
data class FrameAnalysis(
    val freeze: FreezeReading,
    val disconnect: DisconnectCheck,
    /**
     * How the screen was classified *this tick*, or null when no classification ran. It is never
     * a cached reading: the stabilizer counts consecutive readings, so handing it the same one
     * twice would let a single OCR pass confirm a state on its own.
     */
    val reading: ScreenReading?,
    /** Where the Play button was found, if it was. */
    val playButton: NormBox?,
    /** Announcements matched in the notification area this tick. */
    val events: List<GameEventSighting>,
    /** Raw screen text, only collected when debug logging is on. */
    val ocrText: String?,
    /** True when a full-frame OCR pass ran, as opposed to only the cheap checks. */
    val ocrRan: Boolean,
)

/**
 * Runs the staged pipeline over one captured frame.
 *
 * Stage 1 is cheap and runs every tick: an 80x45 grayscale diff for motion, plus a coarse
 * fingerprint of the frame. Stage 2 is OCR, which is expensive and therefore runs only when it
 * can actually change the answer - the picture has changed since the last classification, a
 * heartbeat is due, the caller is verifying something, or the small announcement crop is due.
 * A screen that is sitting still is not re-read; its previous classification stands.
 */
class FrameAnalyzer(private val ocr: OcrEngine = OcrEngine()) {
    private val freeze = FreezeDetector()
    private var lastGrid: LumaGrid? = null
    private var lastStampMs = -1L
    private var lastClassifyMs = -1L
    private var lastEventScanMs = -1L
    private var lastEventScanStamp = -1L
    private var classifiedFingerprint: FrameFingerprint? = null

    /** The most recent classification, reused while the picture has not meaningfully changed. */
    var lastReading: ScreenReading? = null
        private set

    suspend fun analyze(
        frame: CapturedFrame,
        nowMs: Long,
        settings: WatchdogSettings,
        game: ActiveGame,
        foreground: Boolean?,
        forceOcr: Boolean,
    ): FrameAnalysis {
        // ---- Stage 1: cheap, every tick ----
        val cached = lastGrid
        val grid = if (cached != null && frame.frameStampMs == lastStampMs) {
            cached
        } else {
            withContext(Dispatchers.Default) { frame.bitmap.toLumaGrid() }
        }
        lastStampMs = frame.frameStampMs
        lastGrid = grid

        val reading = freeze.update(grid, nowMs, game.mask, game.config.minMotionPercent / 100.0)
        val fingerprint = FrameFingerprint.of(grid)

        // Roblox not being on screen is decided without OCR at all.
        if (foreground == false) {
            // This is a fresh conclusion every tick - it needs no OCR to reach.
            lastReading = ScreenClassifier.classify(ScreenEvidence(robloxForeground = false), game.profile.screenMarkers)
            return FrameAnalysis(reading, DisconnectCheck.NotChecked, lastReading, null, emptyList(), null, ocrRan = false)
        }

        // ---- Stage 2: decide whether OCR can change anything ----
        val sinceClassify = if (lastClassifyMs < 0) Long.MAX_VALUE else nowMs - lastClassifyMs
        val pictureChanged = classifiedFingerprint?.differsFrom(fingerprint) ?: true
        val heartbeat = sinceClassify >= settings.ocrHeartbeatSec * 1000L
        val classifyDue = forceOcr || pictureChanged || heartbeat

        val eventsOn = game.has(Feature.EVENT_ALERTS) && game.eventRules.isNotEmpty()
        val eventDue = eventsOn &&
            frame.frameStampMs != lastEventScanStamp &&
            (lastEventScanMs < 0 || nowMs - lastEventScanMs >= game.config.eventScanSec * 1000L - 250)

        if (!classifyDue && !eventDue) {
            return FrameAnalysis(reading, DisconnectCheck.NotChecked, null, null, emptyList(), null, ocrRan = false)
        }

        val seen = mutableListOf<OcrLine>()
        var disconnect: DisconnectCheck = DisconnectCheck.NotChecked
        var playButton: NormBox? = null
        var fresh: ScreenReading? = null

        if (classifyDue) {
            lastClassifyMs = nowMs
            classifiedFingerprint = fingerprint
            val lines = ocr.recognize(frame.bitmap, OcrEngine.FULL_FRAME)
            seen += lines

            val match = if (game.has(Feature.DISCONNECT_DETECTION)) {
                DisconnectTextMatcher.match(
                    lines.filter { OcrEngine.inDialogRegion(it) },
                    game.profile.extraDisconnectPhrases,
                )
            } else {
                null
            }
            disconnect = match?.let { DisconnectCheck.Found(it) } ?: DisconnectCheck.Clear

            val evidence = ScreenEvidence(
                robloxForeground = foreground,
                lines = lines,
                ocrRan = true,
                disconnect = match,
                stillForMs = reading.stillForMs,
            )
            fresh = ScreenClassifier.classify(evidence, game.profile.screenMarkers)
            lastReading = fresh
            playButton = ScreenClassifier.findPlayButton(lines, game.profile.screenMarkers)?.box
        }

        // The announcement crop is small, so it can be read far more often than the whole screen.
        val events = if (eventDue) {
            lastEventScanMs = nowMs
            lastEventScanStamp = frame.frameStampMs
            val rules = game.eventRules
            val region = rules.first().region
            val lines = if (region == FULL && classifyDue) {
                seen.toList() // the full pass already covers it
            } else {
                ocr.recognize(frame.bitmap, region).also { seen += it }
            }
            GameEventDetector.detect(lines, rules)
        } else {
            emptyList()
        }

        val ocrText = if (settings.debugLogOcr && seen.isNotEmpty()) {
            // Position as percentages, so screen regions can be tuned from the log.
            seen.joinToString(" | ") { l ->
                val at = l.box?.let { "@${(it.centerX * 100).toInt()},${(it.centerY * 100).toInt()}%" } ?: ""
                l.text.trim() + at
            }.also { Timber.i("OCR: %s", it) }
        } else {
            null
        }

        return FrameAnalysis(reading, disconnect, fresh, playButton, events, ocrText, ocrRan = classifyDue)
    }

    fun close() = ocr.close()

    fun reset() {
        freeze.reset()
        lastGrid = null
        lastStampMs = -1
        lastClassifyMs = -1
        lastEventScanMs = -1
        lastEventScanStamp = -1
        classifiedFingerprint = null
        lastReading = null
    }

    private companion object {
        val FULL = NormBox(0f, 0f, 1f, 1f)
    }
}

/**
 * A very coarse summary of a frame - the average brightness of a 4x4 grid of tiles.
 *
 * It exists to answer one question cheaply: has the screen changed enough that re-reading its
 * text could give a different answer? A still welcome screen, a paused loading screen and an
 * idle HUD all keep the same fingerprint, so OCR is skipped entirely while they sit there.
 */
class FrameFingerprint(private val tiles: IntArray) {
    fun differsFrom(other: FrameFingerprint, tolerance: Int = 6): Boolean {
        if (tiles.size != other.tiles.size) return true
        for (i in tiles.indices) {
            if (kotlin.math.abs(tiles[i] - other.tiles[i]) > tolerance) return true
        }
        return false
    }

    companion object {
        const val TILES = 4

        fun of(grid: LumaGrid): FrameFingerprint {
            val out = IntArray(TILES * TILES)
            val counts = IntArray(TILES * TILES)
            for (y in 0 until grid.height) {
                val ty = y * TILES / grid.height
                for (x in 0 until grid.width) {
                    val tx = x * TILES / grid.width
                    val t = ty * TILES + tx
                    out[t] += grid.luma[y * grid.width + x]
                    counts[t]++
                }
            }
            for (i in out.indices) if (counts[i] > 0) out[i] /= counts[i]
            return FrameFingerprint(out)
        }
    }
}
