package com.pdfreader.app.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single app-wide connectivity source. Reports "online" only when the network is
 * VALIDATED (real internet), and fires [onBackOnline] exactly once per offline→online
 * transition — this is the trigger for "push my offline reading first".
 */
object NetworkMonitor {

    private const val TAG = "NetworkMonitor"
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private var registered = false

    fun start(context: Context, onBackOnline: () -> Unit) {
        if (registered) return
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        _online.value = isValidated(cm)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val nowOnline = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val wasOnline = _online.value
                _online.value = nowOnline
                if (nowOnline && !wasOnline) {
                    Log.d(TAG, "Back online (validated) → sync")
                    onBackOnline()
                }
            }
            override fun onLost(network: Network) {
                _online.value = isValidated(cm)
                Log.d(TAG, "Network lost → online=${_online.value}")
            }
        })
        registered = true
    }

    private fun isValidated(cm: ConnectivityManager): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
