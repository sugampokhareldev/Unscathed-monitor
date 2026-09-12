package com.unscathed.monitor.system

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

data class NetworkInfo(
    val connected: Boolean = false,
    /** Android confirmed the network actually reaches the internet. */
    val validated: Boolean = false,
    val transport: String = "None",
) {
    val label: String
        get() = when {
            !connected -> "Offline"
            validated -> "$transport ✓"
            else -> "$transport (no internet)"
        }
}

class NetworkMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(NetworkInfo())
    val state: StateFlow<NetworkInfo> = _state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _state.value = caps.toInfo()
        }

        override fun onLost(network: Network) {
            _state.value = NetworkInfo()
        }
    }

    fun start() {
        _state.value = cm.getNetworkCapabilities(cm.activeNetwork)?.toInfo() ?: NetworkInfo()
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onFailure { Timber.e(it, "Could not register network callback") }
    }

    private fun NetworkCapabilities.toInfo() = NetworkInfo(
        connected = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        validated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        transport = when {
            hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Other"
        },
    )
}
