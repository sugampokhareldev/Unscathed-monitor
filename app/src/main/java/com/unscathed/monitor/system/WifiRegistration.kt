package com.unscathed.monitor.system

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi

/**
 * Saves the user's Wi-Fi with the phone once so Android auto-rejoins it whenever it's in range.
 * The password goes straight to the system and is never stored by this app.
 */
object WifiRegistration {
    sealed interface Result {
        data class Done(val message: String) : Result
        data class Failed(val message: String) : Result
        /** Android 11+: launch this and pass its result to [parseAddNetworksResult]. */
        data class NeedsConfirmation(val intent: Intent) : Result
    }

    fun register(context: Context, ssid: String, password: String): Result {
        if (ssid.isBlank()) return Result.Failed("Enter the Wi-Fi name first")
        if (password.isNotEmpty() && password.length !in 8..63) {
            return Result.Failed("WPA2 passwords are 8-63 characters")
        }
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                    .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion(ssid, password)))
                Result.NeedsConfirmation(intent)
            }
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> addSuggestion(context, ssid, password)
            else -> Result.Failed("On Android 8-9, save the network in Wi-Fi settings. The app will call reconnect() for you.")
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun parseAddNetworksResult(data: Intent?): Result {
        val codes = data?.getIntegerArrayListExtra(Settings.EXTRA_WIFI_NETWORK_RESULT_LIST)
            ?: return Result.Failed("Cancelled")
        return when (codes.firstOrNull()) {
            Settings.ADD_WIFI_RESULT_SUCCESS -> Result.Done("Saved. Android will rejoin it automatically.")
            Settings.ADD_WIFI_RESULT_ALREADY_EXISTS -> Result.Done("Already saved on this phone ✓")
            else -> Result.Failed("Android couldn't save the network")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun addSuggestion(context: Context, ssid: String, password: String): Result {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        wm.removeNetworkSuggestions(emptyList()) // replace any earlier suggestion from this app
        return when (wm.addNetworkSuggestions(listOf(suggestion(ssid, password)))) {
            WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS,
            WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE ->
                Result.Done("Suggested. Approve the 'Allow suggested networks?' notification if Android shows one.")
            else -> Result.Failed("Android rejected the suggestion")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun suggestion(ssid: String, password: String): WifiNetworkSuggestion =
        WifiNetworkSuggestion.Builder()
            .setSsid(ssid)
            .apply { if (password.isNotEmpty()) setWpa2Passphrase(password) }
            .setIsAppInteractionRequired(false)
            .build()
}
