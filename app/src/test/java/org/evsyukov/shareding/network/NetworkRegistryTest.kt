package org.evsyukov.shareding.network

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkRegistryTest {
    @Test fun triesPreferredThenOtherNetworksThenDefault() {
        val registry = NetworkRegistry<String>()
        registry.available("wifi")
        registry.available("vpn")

        assertEquals(listOf("vpn", "wifi", null), registry.candidates("vpn"))
        assertEquals(listOf("wifi", "vpn", null), registry.candidates(null))

        registry.lost("wifi")
        assertEquals(listOf("vpn", null), registry.candidates(null))
    }

    @Test fun notifiesOnlyNewNetworksWhileSubscribed() {
        val registry = NetworkRegistry<String>()
        registry.available("wifi")
        val observed = mutableListOf<String>()
        val unsubscribe = registry.subscribe { observed += it }

        registry.available("wifi")
        registry.available("vpn")
        registry.lost("wifi")
        registry.available("wifi")
        unsubscribe()
        registry.available("cellular")

        assertEquals(listOf("vpn", "wifi"), observed)
        assertEquals(listOf("vpn", "wifi", "cellular", null), registry.candidates(null))
    }
}
