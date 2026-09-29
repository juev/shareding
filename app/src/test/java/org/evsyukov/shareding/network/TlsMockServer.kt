package org.evsyukov.shareding.network

import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

internal class TlsMockServer(tunnelProxy: Boolean = false) : AutoCloseable {
    private val certificate = HeldCertificate.Builder()
        .commonName("localhost")
        .addSubjectAlternativeName("localhost")
        .addSubjectAlternativeName("127.0.0.1")
        .addSubjectAlternativeName("origin.invalid")
        .addSubjectAlternativeName("linkding.invalid")
        .build()
    private val serverCertificates = HandshakeCertificates.Builder()
        .heldCertificate(certificate)
        .build()
    private val clientCertificates = HandshakeCertificates.Builder()
        .addTrustedCertificate(certificate.certificate)
        .build()

    val server = MockWebServer().apply {
        useHttps(serverCertificates.sslSocketFactory(), tunnelProxy)
    }

    fun api(proxyProvider: () -> ProxyConfig = { ProxyConfig() }): LinkdingApi = LinkdingApi(
        clientFactory = { config, network, connectSeconds, readSeconds ->
            ProxyHttpClient.create(config, network, connectSeconds, readSeconds)
                .newBuilder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .build()
        },
        proxyProvider = proxyProvider,
    )

    override fun close() = server.close()
}
