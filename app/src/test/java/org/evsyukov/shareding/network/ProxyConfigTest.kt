package org.evsyukov.shareding.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProxyConfigTest {
    @Test fun acceptsHostAndPortAndOptionalCredentials() {
        assertEquals("proxy.example", ProxyConfig(true, "proxy.example", 3128).validated().host)
        assertEquals("alice", ProxyConfig(true, "127.0.0.1", 8080, "alice", "secret").validated().username)
    }

    @Test fun rejectsInvalidEnabledProxy() {
        listOf(
            ProxyConfig(true, "", 8080),
            ProxyConfig(true, "https://proxy.example", 8080),
            ProxyConfig(true, "proxy.example/path", 8080),
            ProxyConfig(true, "proxy.example", 0),
            ProxyConfig(true, "proxy.example", 65536),
            ProxyConfig(true, "proxy.example", 8080, "", "secret"),
        ).forEach { config ->
            val result = runCatching { config.validated() }
            assertFalse("Accepted $config", result.isSuccess)
        }
    }

    @Test fun disabledProxyNeedsNoHost() {
        ProxyConfig().validated()
    }
}
