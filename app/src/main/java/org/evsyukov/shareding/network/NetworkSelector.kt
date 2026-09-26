package org.evsyukov.shareding.network

import android.net.Network
import kotlinx.coroutines.CancellationException

class NetworkSelector(private val networks: NetworkTracker, private val api: LinkdingApi,
                      private val pageFetcher: PageMetadataFetcher) {
    suspend fun checkAndSelect(server: String, token: String, preferred: Network? = null,
                               proxy: ProxyConfig? = null): Network? =
        select(preferred) { network ->
            api.check(server, token, network, proxy)
            network
        }

    suspend fun listTags(server: String, token: String): List<String> =
        select(null) { network -> api.listTags(server, token, network) }

    suspend fun fetchPageMetadata(url: String, preferred: Network? = null,
                                  proxy: ProxyConfig? = null): PageMetadata? =
        select(preferred) { network -> pageFetcher.fetch(url, network, proxy) }

    private suspend fun <T> select(preferred: Network?, action: suspend (Network?) -> T): T {
        var lastError: Exception? = null
        for (network in networks.candidates(preferred)) {
            try {
                return action(network)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (error is ApiException && error.code == 401) throw error
                lastError = error
            }
        }
        throw lastError ?: IllegalStateException("No network can reach linkding")
    }
}
