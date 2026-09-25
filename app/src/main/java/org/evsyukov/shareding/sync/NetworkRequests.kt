package org.evsyukov.shareding.sync

import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build

object NetworkRequests {
    fun linkdingCandidate(): NetworkRequest {
        val builder = NetworkRequest.Builder()
        if (Build.VERSION.SDK_INT >= 30) {
            builder.clearCapabilities()
        } else {
            builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
        return builder.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
    }

    fun anyConnected(): NetworkRequest {
        val builder = NetworkRequest.Builder()
        if (Build.VERSION.SDK_INT >= 30) {
            builder.clearCapabilities()
        } else {
            builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
            builder.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
        return builder.build()
    }
}
