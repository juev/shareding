package org.evsyukov.shareding

import android.content.Context
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy

internal class AndroidTestTls(context: Context) {
    private val sslSocketFactory = run {
        val password = "test-password".toCharArray()
        val keystore = KeyStore.getInstance("PKCS12").apply {
            context.assets.open("test_tls.p12").use { load(it, password) }
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keystore, password)
        }
        SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }.socketFactory
    }

    fun start(server: MockWebServer, tunnelProxy: Boolean = false) {
        server.useHttps(sslSocketFactory, tunnelProxy)
    }

    fun url(server: MockWebServer, path: String = "/"): String =
        server.url(path).newBuilder().scheme("https").build().toString()

    fun connectResponse(): MockResponse = MockResponse()
        .setResponseCode(200)
        .setSocketPolicy(SocketPolicy.UPGRADE_TO_SSL_AT_END)
}
