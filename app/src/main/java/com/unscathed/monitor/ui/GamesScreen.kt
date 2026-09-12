package com.unscathed.monitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unscathed.monitor.config.GameConfig
import com.unscathed.monitor.container
import com.unscathed.monitor.games.Feature
import com.unscathed.monitor.games.GameCatalog
import com.unscathed.monitor.games.GameLinks
import com.unscathed.monitor.games.GameProfile
import kotlinx.coroutines.launch

@Composable
fun GamesScreen(modifier: Modifier = Modifier) {
    val app = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val settings by app.settings.state.collectAsStateWithLifecycle()
    var markingAreaFor by rememberSaveable { mutableStateOf<String?>(null) }

    markingAreaFor?.let { id ->
        EventAreaScreen(GameCatalog.byId(id), modifier) { markingAreaFor = null }
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        GameCatalog.byCategory().forEach { (category, games) ->
            Text(
                "${category.icon}  ${category.label.uppercase()}",
                color = Palette.Muted,
                letterSpacing = 2.sp,
                fontSize = 13.sp,
            )
            games.forEach { profile ->
                GameCard(
                    profile = profile,
                    saved = settings.configFor(profile),
                    active = settings.activeGameId == profile.id,
                    onMarkArea = { markingAreaFor = profile.id },
                    onActivate = { scope.launch { app.settings.update { it.copy(activeGameId = profile.id) } } },
                    onSave = { cfg -> scope.launch { app.settings.update { it.withGameConfig(profile, cfg) } } },
                )
            }
        }
        Hint("More games and categories plug into the same catalog. Only Unscathed is built in for now.")
    }
}

@Composable
private fun GameCard(
    profile: GameProfile,
    saved: GameConfig,
    active: Boolean,
    onMarkArea: () -> Unit,
    onActivate: () -> Unit,
    onSave: (GameConfig) -> Unit,
) {
    var draft by remember(saved) { mutableStateOf(saved) }
    var linkInput by remember(saved.placeId) { mutableStateOf(saved.placeId) }
    var showTuning by remember { mutableStateOf(false) }
    val dirty = draft != saved

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(profile.name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(profile.tagline, fontSize = 12.sp, color = Palette.Muted)
            }
            if (active) {
                Text(
                    "ACTIVE",
                    color = Palette.Background,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .background(Palette.Green, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            } else {
                TextButton(onClick = onActivate) { Text("Watch this game") }
            }
        }

        Spacer(Modifier.padding(4.dp))
        Text("Game to rejoin", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.padding(2.dp))
        TextSetting("Game link or place ID", linkInput, "https://www.roblox.com/games/...") { input ->
            linkInput = input
            val parsed = GameLinks.parsePlaceId(input)
            draft = draft.copy(placeId = parsed ?: if (input.isBlank()) "" else draft.placeId)
        }
        when {
            draft.placeId.isNotBlank() -> Hint("Place ID ${draft.placeId}", Palette.Green)
            linkInput.isNotBlank() -> Hint("No place ID in that. Paste the game's page link.", Palette.Amber)
            else -> Hint("Not set: rejoin will only open Roblox. Paste the Unscathed game link.", Palette.Amber)
        }
        val suggested = profile.suggestedPlaceId
        if (suggested != null && draft.placeId != suggested) {
            TextButton(onClick = {
                linkInput = suggested
                draft = draft.copy(placeId = suggested)
            }) { Text("Use ${profile.suggestedPlaceName ?: profile.name} ($suggested)") }
        }
        TextSetting("Private server link (optional)", draft.privateServerLink, "share link, type=Server") {
            draft = draft.copy(privateServerLink = it)
        }
        if (draft.privateServerLink.isNotBlank() && !GameLinks.isPrivateServerLink(draft.privateServerLink)) {
            Hint("That does not look like a private server link; it will still be tried first.", Palette.Amber)
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Palette.SurfaceHigh)
        Text("Features", fontWeight = FontWeight.SemiBold)
        profile.supportedFeatures.sortedBy { it.ordinal }.forEach { feature ->
            val on = feature in draft.features
            SwitchSetting(feature.label, on, feature.description) { enabled ->
                draft = draft.copy(features = if (enabled) draft.features + feature else draft.features - feature)
            }
        }
        if (Feature.KEEP_RETRYING in draft.features && Feature.AUTO_REJOIN !in draft.features) {
            Hint("'Never give up' needs 'Rejoin game' on.", Palette.Amber)
        }

        if (Feature.AUTO_PLAY in draft.features) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Palette.SurfaceHigh)
            Text("Auto Play", fontWeight = FontWeight.SemiBold)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Hint(
                    "When the welcome screen is confirmed and the Play button has been found, the app taps it once " +
                        "and then waits to see the game load. It never taps again until that has been decided.",
                )
                NumberSetting("Wait after tapping Play (seconds)", draft.playVerifySec) {
                    draft = draft.copy(playVerifySec = it)
                }
                NumberSetting("Shortest gap between taps (seconds)", draft.playCooldownSec) {
                    draft = draft.copy(playCooldownSec = it)
                }
                NumberSetting("Failed taps before pausing Auto Play", draft.playMaxAttempts) {
                    draft = draft.copy(playMaxAttempts = it)
                }
            }
        }

        if (Feature.EVENT_ALERTS in draft.features && profile.events.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Palette.SurfaceHigh)
            Text("In-game announcements", fontWeight = FontWeight.SemiBold)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                profile.events.forEach { rule ->
                    val on = rule.id !in draft.disabledEventIds
                    SwitchSetting(rule.title, on, "Watches for \"${rule.subject} ... appeared\"") { enabled ->
                        draft = draft.copy(
                            disabledEventIds = if (enabled) draft.disabledEventIds - rule.id
                            else draft.disabledEventIds + rule.id,
                        )
                    }
                }
                NumberSetting("Screen reads needed to confirm", draft.eventConfirmScans) {
                    draft = draft.copy(eventConfirmScans = it)
                }
                Hint("2 or more means one misread can never send an alert on its own.")
                NumberSetting("Do not alert again for (minutes)", draft.eventCooldownMinutes) {
                    draft = draft.copy(eventCooldownMinutes = it)
                }
                NumberSetting("Read the announcement area every (seconds)", draft.eventScanSec) {
                    draft = draft.copy(eventScanSec = it)
                }

                val areaSet = draft.eventIgnoreTopPercent + draft.eventIgnoreBottomPercent +
                    draft.eventIgnoreLeftPercent + draft.eventIgnoreRightPercent > 0
                Button(onClick = onMarkArea, modifier = Modifier.fillMaxWidth()) {
                    Text(if (areaSet) "Announcement area: set - change it" else "Show the app where announcements appear")
                }
                Hint(
                    if (!areaSet) {
                        "The whole screen is read, which is slower and lets chat text near-match. Marking the " +
                            "notification area makes this both cheaper and stricter."
                    } else {
                        "Only the marked part of the screen is read for announcements."
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberSetting("Top %", draft.eventIgnoreTopPercent, Modifier.weight(1f)) {
                        draft = draft.copy(eventIgnoreTopPercent = it)
                    }
                    NumberSetting("Bottom %", draft.eventIgnoreBottomPercent, Modifier.weight(1f)) {
                        draft = draft.copy(eventIgnoreBottomPercent = it)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberSetting("Left %", draft.eventIgnoreLeftPercent, Modifier.weight(1f)) {
                        draft = draft.copy(eventIgnoreLeftPercent = it)
                    }
                    NumberSetting("Right %", draft.eventIgnoreRightPercent, Modifier.weight(1f)) {
                        draft = draft.copy(eventIgnoreRightPercent = it)
                    }
                }
            }
        }

        TextButton(onClick = { showTuning = !showTuning }) {
            Text(if (showTuning) "Hide detection tuning" else "Detection tuning")
        }
        if (showTuning) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberSetting("Readings needed to confirm a screen", draft.confirmScans) {
                    draft = draft.copy(confirmScans = it)
                }
                Hint(
                    "Higher is slower to react but far less likely to act on a misread. 3 is a good default; " +
                        "disconnects and crashes always confirm one reading sooner than this.",
                )
                SwitchSetting(
                    "Rejoin when Roblox is on its own home screen",
                    draft.rejoinWhenNotInGame,
                    "Off if you want to browse Roblox without the app dragging you back in",
                ) { draft = draft.copy(rejoinWhenNotInGame = it) }
                NumberSetting("Frozen after still for (seconds)", draft.freezeSeconds) {
                    draft = draft.copy(freezeSeconds = it)
                }
                DecimalSetting("Min. motion to count as moving (%)", draft.minMotionPercent) {
                    draft = draft.copy(minMotionPercent = it)
                }
                Hint("Ignore screen edges that animate by themselves (timers, chat, counters):")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberSetting("Top %", draft.ignoreTopPercent, Modifier.weight(1f)) {
                        draft = draft.copy(ignoreTopPercent = it)
                    }
                    NumberSetting("Bottom %", draft.ignoreBottomPercent, Modifier.weight(1f)) {
                        draft = draft.copy(ignoreBottomPercent = it)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberSetting("Left %", draft.ignoreLeftPercent, Modifier.weight(1f)) {
                        draft = draft.copy(ignoreLeftPercent = it)
                    }
                    NumberSetting("Right %", draft.ignoreRightPercent, Modifier.weight(1f)) {
                        draft = draft.copy(ignoreRightPercent = it)
                    }
                }
                NumberSetting("Reconnect taps before rejoining", draft.maxReconnectAttempts) {
                    draft = draft.copy(maxReconnectAttempts = it)
                }
                NumberSetting("Quick rejoins before backoff / giving up", draft.maxRelaunchAttempts) {
                    draft = draft.copy(maxRelaunchAttempts = it)
                }
                TextSetting("Never auto-reconnect on error codes", draft.noRecoverCodes, "268, 273") {
                    draft = draft.copy(noRecoverCodes = it)
                }
            }
        }

        Spacer(Modifier.padding(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    draft = GameConfig.defaultsFor(profile).copy(
                        placeId = draft.placeId,
                        privateServerLink = draft.privateServerLink,
                    )
                },
                modifier = Modifier.weight(1f),
            ) { Text("Defaults") }
            Spacer(Modifier.width(0.dp))
            Button(
                onClick = { onSave(draft) },
                enabled = dirty,
                modifier = Modifier.weight(1f),
            ) { Text(if (dirty) "Save" else "Saved") }
        }
    }
}
