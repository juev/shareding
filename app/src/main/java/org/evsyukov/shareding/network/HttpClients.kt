package org.evsyukov.shareding.network

import android.net.Network
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.OkHttpClient

internal object HttpClients {
    fun create(network: Network?, connectSeconds: Long, readSeconds: Long): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectSeconds, TimeUnit.SECONDS)
            .readTimeout(readSeconds, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .proxy(Proxy.NO_PROXY)
        if (network != null) {
            builder.socketFactory(network.socketFactory)
            builder.dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    network.getAllByName(hostname).toList()
            })
        }
        return builder.build()
    }
}
