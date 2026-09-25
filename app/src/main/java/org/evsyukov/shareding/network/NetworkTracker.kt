package org.evsyukov.shareding.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import org.evsyukov.shareding.sync.NetworkRequests

class NetworkTracker(context: Context) {
    private val state = NetworkRegistry<Network>()
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = state.available(network)
        override fun onLost(network: Network) = state.lost(network)
    }

    init {
        connectivity.registerNetworkCallback(NetworkRequests.anyConnected(), callback)
    }

    fun candidates(preferred: Network?): List<Network?> = state.candidates(preferred)

    fun subscribe(onNewNetwork: () -> Unit): () -> Unit = state.subscribe { onNewNetwork() }
}

internal class NetworkRegistry<T : Any> {
    private val lock = Any()
    private val available = LinkedHashSet<T>()
    private val listeners = LinkedHashSet<(T) -> Unit>()

    fun candidates(preferred: T?): List<T?> {
        val routes = synchronized(lock) { (listOfNotNull(preferred) + available).distinct() }
        return routes + null
    }

    fun subscribe(listener: (T) -> Unit): () -> Unit {
        synchronized(lock) { listeners.add(listener) }
        return { synchronized(lock) { listeners.remove(listener) }; Unit }
    }

    fun available(network: T) {
        val callbacks = synchronized(lock) {
            if (available.add(network)) listeners.toList() else emptyList()
        }
        callbacks.forEach { it(network) }
    }

    fun lost(network: T) {
        synchronized(lock) { available.remove(network) }
    }
}
