package com.unscathed.monitor.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unscathed.monitor.container
import com.unscathed.monitor.data.EventType
import com.unscathed.monitor.util.formatDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The session log. Everything the watchdog decided, in order, with timestamps - and a way to
 * get it off the phone when something needs looking at properly.
 */
@Composable
fun LogScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val flow = remember { app.events.recent() }
    val events by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    var exportNote by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val text = events.joinToString("\n") {
                            "${formatDateTime(it.timestampMs)}\t${it.type}\t${it.title}\t${it.detail}"
                        }
                        val file = File(context.filesDir, LOG_FILE)
                        val ok = withContext(Dispatchers.IO) { runCatching { file.writeText(text) }.isSuccess }
                        exportNote = if (ok) "Saved ${events.size} events" else "Could not write the file"
                        if (ok) shareFile(context, file)?.let { exportNote = it }
                    }
                },
                enabled = events.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) { Text("Export log") }
        }
        exportNote?.let {
            Text(it, fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.padding(horizontal = 16.dp))
        }

        if (events.isEmpty()) {
            Text(
                "No events yet. Start monitoring from the Dashboard.",
                color = Palette.Muted,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            items(events, key = { it.id }) { event ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(
                        event.type,
                        color = typeColor(event.type),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(76.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(event.title, fontWeight = FontWeight.Medium)
                        if (event.detail.isNotBlank()) {
                            Text(event.detail, fontSize = 12.sp, color = Palette.Muted, maxLines = 3)
                        }
                        Text(formatDateTime(event.timestampMs), fontSize = 11.sp, color = Palette.Muted)
                    }
                }
                HorizontalDivider(color = Palette.SurfaceHigh)
            }
        }
    }
}

/** Returns an error message, or null when the share sheet opened. */
private fun shareFile(context: android.content.Context, file: File): String? = try {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    context.startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            "Send the log",
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    null
} catch (e: Exception) {
    "Saved to ${file.name}, but nothing could open it: ${e.message}"
}

private const val LOG_FILE = "watchdog-log.txt"

private fun typeColor(type: String): Color = when (type) {
    EventType.ALERT -> Palette.Red
    EventType.RECOVERY -> Palette.Blue
    EventType.STATE -> Palette.Green
    EventType.ERROR -> Palette.Orange
    else -> Palette.Muted
}
