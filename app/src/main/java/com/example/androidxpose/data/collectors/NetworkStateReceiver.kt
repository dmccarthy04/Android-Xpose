package com.example.androidxpose.data.collectors

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.example.androidxpose.data.db.NetworkEvent
import com.example.androidxpose.data.db.XposeDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "NetworkStateReceiver"

class NetworkStateReceiver(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var lastPersistedType: String? = null

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val callback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            val type = resolveNetworkType(network)
            Log.d(TAG, "Network available: $type")
            persistEvent(networkType = type, isConnected = true)
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost")
            persistEvent(networkType = "NONE", isConnected = false)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {

            val type = resolveTypeFromCapabilities(capabilities)
            if (type != lastPersistedType) {
                Log.d(TAG, "Network transport changed: $lastPersistedType -> $type")
                persistEvent(networkType = type, isConnected = true)
            }
        }
    }

    fun register() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, callback)
            Log.d(TAG, "NetworkCallback registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NetworkCallback: ${e.message}")
        }
    }

    fun unregister() {
        try {
            connectivityManager.unregisterNetworkCallback(callback)
            Log.d(TAG, "NetworkCallback unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister NetworkCallback: ${e.message}")
        }
    }

    private fun persistEvent(networkType: String, isConnected: Boolean) {
        lastPersistedType = if (isConnected) networkType else null
        val event = NetworkEvent(
            networkType = networkType,
            isConnected = isConnected,
            timestamp = System.currentTimeMillis()
        )
        scope.launch {
            try {
                XposeDatabase.getInstance(context)
                    .networkEventDao()
                    .insert(event)
                Log.d(TAG, "NetworkEvent persisted: type=$networkType connected=$isConnected")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to insert NetworkEvent: ${e.message}")
            }
        }
    }

    private fun resolveNetworkType(network: Network): String {
        val capabilities = connectivityManager.getNetworkCapabilities(network)
            ?: return "OTHER"
        return resolveTypeFromCapabilities(capabilities)
    }

    private fun resolveTypeFromCapabilities(capabilities: NetworkCapabilities): String {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> "WIFI"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "OTHER"
        }
    }
}
