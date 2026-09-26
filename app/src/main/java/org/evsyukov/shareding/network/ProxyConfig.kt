package org.evsyukov.shareding.network

import java.net.URI

data class ProxyConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 8080,
    val username: String = "",
    val password: String = "",
) {
    fun validated(): ProxyConfig {
        if (!enabled) return this
        require(host.isNotBlank() && host == host.trim() && !host.contains("@")) {
            "Enter a proxy host without a scheme or credentials"
        }
        require(port in 1..65535) { "Proxy port must be between 1 and 65535" }
        require(password.isBlank() || username.isNotBlank()) { "Enter a proxy username for the password" }
        val parsed = runCatching { URI("http", null, host, port, null, null, null) }.getOrNull()
        require(parsed?.host != null && parsed.host.equals(host, ignoreCase = true)) {
            "Enter a proxy host without a scheme or path"
        }
        return this
    }

    override fun toString(): String =
        "ProxyConfig(enabled=$enabled, host=$host, port=$port, username=$username, password=<redacted>)"
}
