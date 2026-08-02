package org.freenet.androidnode

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import org.json.JSONObject

internal data class ConnectivitySnapshot(
    val available: Boolean,
    val validated: Boolean,
    val wifi: Boolean,
    val metered: Boolean,
    val vpn: Boolean,
    val networkType: String,
    val activeNetwork: String?,
) {
    val networkModeAllowed: Boolean
        get() = isAllowed(NetworkDataPolicy.UnmeteredOnly)

    fun isAllowed(policy: NetworkDataPolicy): Boolean =
        available && validated && (policy == NetworkDataPolicy.AnyValidated || !metered)

    fun policyBlockReason(
        policy: NetworkDataPolicy = NetworkDataPolicy.UnmeteredOnly,
    ): String = when {
        !available -> "No active network is available"
        !validated -> "The active network has not validated internet access"
        metered && policy == NetworkDataPolicy.UnmeteredOnly ->
            "The active network is metered; the node requires an unmetered network"
        else -> "Network mode is allowed"
    }

    fun toJson(): String = JSONObject()
        .put("available", available)
        .put("validated", validated)
        .put("wifi", wifi)
        .put("metered", metered)
        .put("vpn", vpn)
        .put("networkType", networkType)
        .put("activeNetwork", activeNetwork)
        .toString()

    companion object {
        fun unavailable() = ConnectivitySnapshot(
            available = false,
            validated = false,
            wifi = false,
            metered = false,
            vpn = false,
            networkType = "Unavailable",
            activeNetwork = null,
        )
    }
}

internal class AndroidConnectivityMonitor(
    context: Context,
    private val onChanged: (ConnectivitySnapshot) -> Unit,
) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private var registered = false
    private var lastPublished: ConnectivitySnapshot? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publishCurrent()

        override fun onLost(network: Network) = publishCurrent()

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) = publishCurrent()

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) =
            publishCurrent()
    }

    fun currentSnapshot(): ConnectivitySnapshot {
        val active = manager.activeNetwork ?: return ConnectivitySnapshot.unavailable()
        val capabilities = manager.getNetworkCapabilities(active)
            ?: return ConnectivitySnapshot.unavailable()
        val vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val type = when {
            vpn -> "VPN"
            wifi -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            else -> "Other"
        }
        return ConnectivitySnapshot(
            available = true,
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            wifi = wifi,
            metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            vpn = vpn,
            networkType = type,
            activeNetwork = active.toString(),
        )
    }

    fun register() {
        if (registered) return
        manager.registerDefaultNetworkCallback(callback)
        registered = true
        publishCurrent()
    }

    fun unregister() {
        if (!registered) return
        manager.unregisterNetworkCallback(callback)
        registered = false
    }

    private fun publishCurrent() {
        val snapshot = currentSnapshot()
        synchronized(this) {
            if (snapshot == lastPublished) return
            lastPublished = snapshot
        }
        onChanged(snapshot)
    }
}
