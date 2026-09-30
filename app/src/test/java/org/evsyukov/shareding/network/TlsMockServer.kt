package org.evsyukov.shareding.network

import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

internal class TlsMockServer : AutoCloseable {
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
        useHttps(serverCertificates.sslSocketFactory(), false)
    }

    fun api(): LinkdingApi = LinkdingApi(
        clientFactory = { network, connectSeconds, readSeconds ->
            HttpClients.create(network, connectSeconds, readSeconds)
                .newBuilder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .build()
        },
    )

    override fun close() = server.close()
}
