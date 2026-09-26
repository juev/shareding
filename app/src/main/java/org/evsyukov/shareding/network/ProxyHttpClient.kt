package org.evsyukov.shareding.network

import android.net.Network
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient

internal object ProxyHttpClient {
    fun create(config: ProxyConfig, network: Network?, connectSeconds: Long,
               readSeconds: Long): OkHttpClient {
        config.validated()
        val builder = OkHttpClient.Builder()
            .connectTimeout(connectSeconds, TimeUnit.SECONDS)
            .readTimeout(readSeconds, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
        if (config.enabled) {
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(config.host, config.port)))
            if (config.username.isNotBlank()) {
                builder.proxyAuthenticator { _, response ->
                    if (response.request.header("Proxy-Authorization") != null) null
                    else response.request.newBuilder()
                        .header("Proxy-Authorization", Credentials.basic(config.username, config.password))
                        .build()
                }
            }
        } else {
            builder.proxy(Proxy.NO_PROXY)
        }
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
