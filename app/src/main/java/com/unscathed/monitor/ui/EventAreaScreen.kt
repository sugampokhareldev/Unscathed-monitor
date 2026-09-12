package com.unscathed.monitor.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unscathed.monitor.container
import com.unscathed.monitor.games.GameProfile
import com.unscathed.monitor.service.WatchdogRuntime
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Lets the user draw a box around the part of the screen where in-game announcements appear.
 *
 * Cropping is worth doing twice over: OCR on a strip is far cheaper than OCR on the whole
 * screen, and text outside the strip - chat, quest names, other players - can no longer
 * near-match an announcement.
 */
@Composable
fun EventAreaScreen(profile: GameProfile, modifier: Modifier = Modifier, onDone: () -> Unit) {
    val app = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val status by WatchdogRuntime.status.collectAsStateWithLifecycle()
    val config = settings.configFor(profile)

    // Normalized box being drawn (0..1 of the screenshot).
    var box by remember(config) {
        mutableStateOf(
            Rect01(
                left = config.eventIgnoreLeftPercent / 100f,
                top = config.eventIgnoreTopPercent / 100f,
                right = 1f - config.eventIgnoreRightPercent / 100f,
                bottom = 1f - config.eventIgnoreBottomPercent / 100f,
            ),
        )
    }
    var requested by remember { mutableStateOf(false) }

    val bitmap = remember(status.calibrationPath, status.calibrationVersion) {
        status.calibrationPath?.let { BitmapFactory.decodeFile(it) }
    }

    // Ask the running service for a fresh frame as soon as this screen opens.
    LaunchedEffect(Unit) {
        WatchdogRuntime.requestFrame()
        requested = true
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Announcement area", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Hint(
            "Start monitoring, get into ${profile.name}, then come back here and tap Capture screen. " +
                "Drag a box around the strip where messages like \"The Dark Arts merchant has appeared!\" show up.",
        )

        Panel {
            if (bitmap == null) {
                Text(
                    if (requested) {
                        "Waiting for a frame. Monitoring has to be running for the app to capture one."
                    } else {
                        "No screenshot yet."
                    },
                    color = Palette.Muted,
                )
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                        .pointerInput(bitmap) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    val x = (start.x / size.width).coerceIn(0f, 1f)
                                    val y = (start.y / size.height).coerceIn(0f, 1f)
                                    box = Rect01(x, y, x, y)
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val x = (change.position.x / size.width).coerceIn(0f, 1f)
                                    val y = (change.position.y / size.height).coerceIn(0f, 1f)
                                    box = box.copy(right = x, bottom = y)
                                },
                            )
                        }
                        .drawBehind {
                            val n = box.normalized()
                            val topLeft = Offset(n.left * size.width, n.top * size.height)
                            val boxSize = Size((n.right - n.left) * size.width, (n.bottom - n.top) * size.height)
                            drawRect(
                                color = Palette.Green.copy(alpha = 0.18f),
                                topLeft = topLeft,
                                size = boxSize,
                            )
                        },
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "The phone screen, for marking the announcement area",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                val n = box.normalized()
                Text(
                    "Area: ${(n.left * 100).toInt()}% - ${(n.right * 100).toInt()}% across, " +
                        "${(n.top * 100).toInt()}% - ${(n.bottom * 100).toInt()}% down",
                    fontSize = 13.sp,
                    color = Palette.Muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { WatchdogRuntime.requestFrame() },
                modifier = Modifier.weight(1f),
            ) { Text("Capture screen") }
            OutlinedButton(
                onClick = { box = Rect01(0f, 0f, 1f, 1f) },
                modifier = Modifier.weight(1f),
            ) { Text("Whole screen") }
        }

        Button(
            onClick = {
                val n = box.normalized()
                scope.launch {
                    app.settings.update {
                        it.withGameConfig(
                            profile,
                            it.configFor(profile).copy(
                                eventIgnoreLeftPercent = (n.left * 100).toInt(),
                                eventIgnoreTopPercent = (n.top * 100).toInt(),
                                eventIgnoreRightPercent = ((1f - n.right) * 100).toInt(),
                                eventIgnoreBottomPercent = ((1f - n.bottom) * 100).toInt(),
                            ),
                        )
                    }
                    onDone()
                }
            },
            enabled = bitmap != null && box.normalized().let { it.right - it.left > 0.05f && it.bottom - it.top > 0.02f },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save this area") }

        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

/** A drag rectangle in 0..1 screen coordinates; the drag may run in any direction. */
private data class Rect01(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun normalized() = Rect01(
        left = min(left, right),
        top = min(top, bottom),
        right = max(left, right),
        bottom = max(top, bottom),
    )
}
