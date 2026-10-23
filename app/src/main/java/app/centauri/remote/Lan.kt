package app.centauri.remote

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.SocketFactory

/**
 * The network the printer is on, when the default route wouldn't reach it: with mobile data on and
 * a Wi-Fi that has no internet, Android sends everything over mobile data. Null means "use the
 * default", which already is Wi-Fi/Ethernet, or a VPN the user routes the printer through.
 */
internal fun <N : Any> pickLan(active: N?, all: List<N>, has: (N, Int) -> Boolean): N? {
    if (active != null && listOf(TRANSPORT_WIFI, TRANSPORT_ETHERNET, TRANSPORT_VPN).any { has(active, it) }) return null
    return all.firstOrNull { has(it, TRANSPORT_WIFI) || has(it, TRANSPORT_ETHERNET) }
}

/** [pickLan] kept current by the system's network callbacks ([register]), so opening a socket asks nothing. */
class LanTracker {
    private val caps = ConcurrentHashMap<Network, NetworkCapabilities>()
    @Volatile private var defaultRoute: Network? = null
    /** The network to open printer sockets on; null for the default route. */
    @Volatile var network: Network? = null
        private set

    private fun changed() {
        network = pickLan(defaultRoute, caps.keys.toList()) { n, t -> caps[n]?.hasTransport(t) == true }
    }

    val everyNetwork = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) { caps[n] = c; changed() }
        override fun onLost(n: Network) { caps.remove(n); changed() }
    }
    val defaultNetwork = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { defaultRoute = n; changed() }
        override fun onLost(n: Network) { if (defaultRoute == n) defaultRoute = null; changed() }
    }

    fun register(cm: ConnectivityManager) {
        // A Wi-Fi without internet (the printer's) and VPNs count too, which the default request leaves out.
        val any = NetworkRequest.Builder().removeCapability(NET_CAPABILITY_INTERNET).removeCapability(NET_CAPABILITY_NOT_VPN).build()
        cm.registerNetworkCallback(any, everyNetwork)
        cm.registerDefaultNetworkCallback(defaultNetwork)
    }
}

/** A UDP socket on [network], for discovery; left on the default route if binding fails. */
fun lanDatagramSocket(network: Network?): DatagramSocket = DatagramSocket().also { s -> runCatching { network?.bindSocket(s) } }

/** OkHttp's sockets, opened on [network] when it names one (see [LanTracker]). */
class LanSocketFactory(private val network: () -> Network?) : SocketFactory() {
    private fun factory(): SocketFactory = network()?.socketFactory ?: getDefault()
    override fun createSocket(): Socket = factory().createSocket()
    override fun createSocket(host: String, port: Int): Socket = factory().createSocket(host, port)
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        factory().createSocket(host, port, localHost, localPort)
    override fun createSocket(host: InetAddress, port: Int): Socket = factory().createSocket(host, port)
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        factory().createSocket(address, port, localAddress, localPort)
}

/** A typed-in address in dotted IPv4 form ("192.168.1.40"); the printer has no host name to type. */
fun isIpv4(s: String): Boolean = s.split('.').let { parts ->
    parts.size == 4 && parts.all { it.length in 1..3 && it.all(Char::isDigit) && it.toInt() <= 255 }
}
