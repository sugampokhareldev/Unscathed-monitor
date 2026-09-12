package com.unscathed.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unscathed.monitor.config.WebhookConfig
import com.unscathed.monitor.container
import com.unscathed.monitor.games.Feature
import com.unscathed.monitor.games.GameCatalog
import com.unscathed.monitor.network.AlertEvent
import com.unscathed.monitor.network.DiscordAlert
import com.unscathed.monitor.network.DiscordMention
import com.unscathed.monitor.network.SendResult
import com.unscathed.monitor.service.WatchdogRuntime
import kotlinx.coroutines.launch

private const val WEBHOOK_PREFIX = "https://discord.com/api/webhooks/"

/**
 * Everything about reaching you on Discord in one place: where alerts go, who gets tagged,
 * whether the merchant alert is on, and a live connection check.
 */
@Composable
fun DiscordScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val saved by app.settings.state.collectAsStateWithLifecycle()
    val status by WatchdogRuntime.status.collectAsStateWithLifecycle()
    var draft by remember(saved) { mutableStateOf(saved) }
    val testResults = remember { mutableStateMapOf<String, String>() }
    var connection by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val dirty = draft != saved

    val profile = GameCatalog.byId(draft.activeGameId)
    val gameConfig = draft.configFor(profile)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- Connection status ----
        Panel {
            val usable = draft.webhooks.count { it.usable }
            // What we last learned, newest first: this screen's own check, then the one the
            // service ran when monitoring started, then whether a webhook exists at all.
            val checked = connection ?: status.webhookOk?.let { it to (status.webhookNote ?: "") }
            val ok = checked?.first
            val note = checked?.second ?: if (usable == 0) "No webhook set" else "$usable webhook(s), not checked yet"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(12.dp).background(
                        when {
                            usable == 0 -> Palette.Red
                            ok == true -> Palette.Green
                            ok == false -> Palette.Red
                            else -> Palette.Amber
                        },
                        CircleShape,
                    ),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            usable == 0 -> "Not set up"
                            ok == true -> "Connected"
                            ok == false -> "Not working"
                            else -> "Not checked yet"
                        },
                        fontWeight = FontWeight.Bold,
                    )
                    if (note.isNotBlank()) Text(note, fontSize = 12.sp, color = Palette.Muted)
                }
            }
            Spacer(Modifier.size(10.dp))
            OutlinedButton(
                onClick = {
                    connection = null
                    scope.launch {
                        val targets = draft.webhooks.filter { it.usable }
                        if (targets.isEmpty()) {
                            connection = false to "Add a webhook URL below first"
                            return@launch
                        }
                        val failures = targets.mapNotNull { h -> app.webhook.check(h.url)?.let { "${h.name}: $it" } }
                        connection = if (failures.isEmpty()) {
                            true to "${targets.size} webhook(s) answered"
                        } else {
                            false to failures.joinToString("; ")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Check connection") }
            Hint(
                "This asks Discord whether the webhook exists. It does not post anything.",
            )
        }

        Section("Who to tag") {
            TextSetting("Your name (shown in alerts)", draft.pingName, "noobeiiks") {
                draft = draft.copy(pingName = it)
            }
            TextSetting("Your Discord user ID", draft.pingUserId, "e.g. 123456789012345678") {
                draft = draft.copy(pingUserId = it)
            }
            val parsed = DiscordMention.parseUserId(draft.pingUserId)
            when {
                parsed != null -> Hint("Alerts will tag <@$parsed>", Palette.Green)
                draft.pingUserId.isNotBlank() -> Hint(
                    "That is not a user ID. Webhooks cannot tag by username, so \"@${draft.pingName}\" " +
                        "will only show as text.",
                    Palette.Amber,
                )
                else -> Hint(
                    "Without an ID, alerts show \"@${draft.pingName.ifBlank { "you" }}\" as plain text and will not " +
                        "notify you. In Discord: Settings, Advanced, Developer Mode on, then long-press your name " +
                        "and Copy User ID.",
                    Palette.Amber,
                )
            }
            Text("Tag me for:", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            EventChips(draft.pingEvents) { draft = draft.copy(pingEvents = it) }
        }

        Section("Alerts") {
            SwitchSetting("Send to Discord", draft.discordEnabled, "Master switch for every webhook below") {
                draft = draft.copy(discordEnabled = it)
            }
            SwitchSetting(
                "Merchant alert",
                Feature.EVENT_ALERTS in gameConfig.features,
                "Watch for \"The Dark Arts merchant has appeared!\" and tag you when it shows up",
            ) { on ->
                val features = if (on) gameConfig.features + Feature.EVENT_ALERTS
                else gameConfig.features - Feature.EVENT_ALERTS
                draft = draft.withGameConfig(profile, gameConfig.copy(features = features))
            }
            SwitchSetting(
                "Check the webhook before monitoring starts",
                draft.requireWebhookCheck,
                "Refuses to start rather than silently losing every alert for a whole session",
            ) { draft = draft.copy(requireWebhookCheck = it) }
            TextSetting("Device name", draft.deviceName) { draft = draft.copy(deviceName = it) }
        }

        Section("Webhooks") {
            Hint(
                "Add as many as you like, e.g. the merchant in one channel and disconnects in another. " +
                    "In Discord: channel, Edit Channel, Integrations, Webhooks, New Webhook, Copy Webhook URL.",
            )
            draft.webhooks.forEachIndexed { index, hook ->
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Palette.SurfaceHigh)
                WebhookEditor(
                    hook = hook,
                    testResult = testResults[hook.id],
                    onChange = { updated ->
                        draft = draft.copy(webhooks = draft.webhooks.toMutableList().also { it[index] = updated })
                    },
                    onRemove = { draft = draft.copy(webhooks = draft.webhooks.filterNot { it.id == hook.id }) },
                    onTest = {
                        testResults[hook.id] = "Sending..."
                        scope.launch {
                            val result = app.webhook.send(
                                hook.url,
                                DiscordAlert(
                                    event = AlertEvent.WATCHDOG,
                                    title = "TEST ALERT: ${hook.name}",
                                    description = "This webhook works. It will receive: " +
                                        hook.events.sortedBy { it.ordinal }.joinToString(", ") { it.label },
                                    color = DiscordAlert.GREEN,
                                    fields = listOf("Device" to draft.deviceName, "Game" to draft.activeGame.name),
                                    mention = DiscordMention.content(
                                        DiscordMention.parseUserId(draft.pingUserId),
                                        draft.pingName,
                                    ),
                                ),
                                draft.deviceName,
                            )
                            testResults[hook.id] = when (result) {
                                SendResult.Ok -> "Sent - check that the tag arrived"
                                is SendResult.RetryLater -> "Failed: ${result.reason}"
                                is SendResult.Rejected -> "Rejected: ${result.reason}"
                            }
                        }
                    },
                )
            }

            OutlinedButton(
                onClick = {
                    val name = if (draft.webhooks.isEmpty()) "Main" else "Webhook ${draft.webhooks.size + 1}"
                    draft = draft.copy(webhooks = draft.webhooks + WebhookConfig(name = name))
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("Add webhook") }
        }

        PrimaryButton(if (dirty) "Save" else "Saved", enabled = dirty) {
            val toSave = draft
            scope.launch { app.settings.update { toSave } }
        }
    }
}

@Composable
private fun WebhookEditor(
    hook: WebhookConfig,
    testResult: String?,
    onChange: (WebhookConfig) -> Unit,
    onRemove: () -> Unit,
    onTest: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(hook.name.ifBlank { "Webhook" }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Switch(checked = hook.enabled, onCheckedChange = { onChange(hook.copy(enabled = it)) })
        }
        TextSetting("Name", hook.name, "Main / Merchant / Disconnects") { onChange(hook.copy(name = it)) }
        TextSetting("Webhook URL", hook.url, "$WEBHOOK_PREFIX...") { onChange(hook.copy(url = it)) }
        if (hook.url.isNotBlank() && !hook.looksValid) {
            Hint("That does not look like a Discord webhook URL. It should start with $WEBHOOK_PREFIX", Palette.Amber)
        }
        Text("Sends:", fontSize = 13.sp, color = Palette.Muted)
        EventChips(hook.events) { onChange(hook.copy(events = it)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onChange(hook.copy(events = AlertEvent.entries.toSet())) }) { Text("All") }
            TextButton(onClick = { onChange(hook.copy(events = setOf(AlertEvent.MERCHANT))) }) { Text("Merchant only") }
            TextButton(
                onClick = { onChange(hook.copy(events = AlertEvent.entries.toSet() - AlertEvent.MERCHANT)) },
            ) { Text("No merchant") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onTest, enabled = hook.url.isNotBlank(), modifier = Modifier.weight(1f)) {
                Text("Send test")
            }
            OutlinedButton(onClick = onRemove, modifier = Modifier.weight(1f)) { Text("Remove", color = Palette.Red) }
        }
        testResult?.let { Text(it, fontSize = 13.sp, color = Palette.Muted) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventChips(selected: Set<AlertEvent>, onChange: (Set<AlertEvent>) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AlertEvent.entries.forEach { event ->
            val on = event in selected
            FilterChip(
                selected = on,
                onClick = { onChange(if (on) selected - event else selected + event) },
                label = { Text(event.label, fontSize = 12.sp) },
            )
        }
    }
}
