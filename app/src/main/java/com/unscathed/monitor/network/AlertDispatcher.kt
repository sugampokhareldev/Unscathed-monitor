package com.unscathed.monitor.network

import com.unscathed.monitor.config.SettingsRepository
import com.unscathed.monitor.system.NetworkMonitor
import com.unscathed.monitor.util.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Sends each alert to every enabled webhook subscribed to its event, in order. Alerts raised
 * while the phone is offline wait in a small queue and go out once the connection is back,
 * marked as delayed.
 */
class AlertDispatcher(
    private val scope: CoroutineScope,
    private val webhook: DiscordWebhook,
    private val settings: SettingsRepository,
    private val network: NetworkMonitor,
) {
    /** One alert headed to one webhook. The webhook is looked up by id when sending, so edits apply. */
    private class Delivery(val webhookId: String, val alert: DiscordAlert)

    private val queue = ArrayDeque<Delivery>()
    private val lock = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        scope.launch {
            while (isActive) {
                val waitMs = drain()
                withTimeoutOrNull(waitMs) { wake.receive() }
            }
        }
        scope.launch {
            network.state.collect { if (it.connected) wake.trySend(Unit) }
        }
    }

    fun enqueue(alert: DiscordAlert) {
        val s = settings.state.value
        if (!s.discordEnabled) return
        val targets = s.webhooks.filter { it.usable && alert.event in it.events }
        if (targets.isEmpty()) return
        synchronized(lock) {
            targets.forEach { queue.addLast(Delivery(it.id, alert)) }
            while (queue.size > MAX_QUEUE) queue.removeFirst()
        }
        wake.trySend(Unit)
    }

    /** Sends everything it can; returns how long to wait before trying again. */
    private suspend fun drain(): Long {
        while (true) {
            val s = settings.state.value
            val next = synchronized(lock) { queue.firstOrNull() } ?: return IDLE_MS
            val target = s.webhooks.firstOrNull { it.id == next.webhookId }
            if (!s.discordEnabled || target == null || !target.usable) {
                remove(next)
                continue
            }
            if (!network.state.value.connected) return OFFLINE_RETRY_MS

            val ageMs = System.currentTimeMillis() - next.alert.createdAtWallMs
            val outgoing = if (ageMs > 60_000) {
                next.alert.withField("⏱ Delayed", "Queued ${formatDuration(ageMs)} (phone was offline)")
            } else {
                next.alert
            }

            when (val result = webhook.send(target.url, outgoing, s.deviceName)) {
                SendResult.Ok -> remove(next)
                is SendResult.RetryLater -> {
                    Timber.w("Discord send to '%s' deferred: %s", target.name, result.reason)
                    return result.afterMs
                }
                is SendResult.Rejected -> {
                    Timber.e("Webhook '%s' rejected '%s': %s", target.name, next.alert.title, result.reason)
                    remove(next)
                }
            }
        }
    }

    private fun remove(delivery: Delivery) = synchronized(lock) { queue.remove(delivery) }

    private companion object {
        const val MAX_QUEUE = 40
        const val IDLE_MS = 60_000L
        const val OFFLINE_RETRY_MS = 15_000L
    }
}
