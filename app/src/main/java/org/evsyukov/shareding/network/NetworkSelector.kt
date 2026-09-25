package org.evsyukov.shareding.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CancellationException

class NetworkSelector(context: Context, private val api: LinkdingApi) {
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    suspend fun checkAndSelect(server: String, token: String, preferred: Network? = null): Network? =
        select(preferred) { network ->
            api.check(server, token, network)
            network
        }

    suspend fun listTags(server: String, token: String): List<String> =
        select(null) { network -> api.listTags(server, token, network) }

    private suspend fun <T> select(preferred: Network?, action: suspend (Network?) -> T): T {
        val networks = (listOfNotNull(preferred) + connectivity.allNetworks).distinct()
        var lastError: Exception? = null
        for (network in networks + listOf(null)) {
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
