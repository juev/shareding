package org.evsyukov.shareding.network

import android.net.Network
import kotlinx.coroutines.CancellationException

class NetworkSelector(private val networks: NetworkTracker, private val api: LinkdingApi) {
    suspend fun checkAndSelect(server: String, token: String, preferred: Network? = null): Network? =
        select(preferred) { network ->
            api.check(server, token, network)
            network
        }

    suspend fun listTags(server: String, token: String): List<String> =
        select(null) { network -> api.listTags(server, token, network) }

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
