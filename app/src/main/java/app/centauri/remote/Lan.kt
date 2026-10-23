package app.centauri.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
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

@Suppress("DEPRECATION") // allNetworks: the replacement is a callback, for a one-off lookup
fun lanNetwork(cm: ConnectivityManager): Network? =
    pickLan(cm.activeNetwork, cm.allNetworks.toList()) { n, t -> cm.getNetworkCapabilities(n)?.hasTransport(t) == true }

/** A UDP socket on [lanNetwork], for discovery; left on the default route if binding fails. */
fun lanDatagramSocket(ctx: Context): DatagramSocket = DatagramSocket().also { s ->
    runCatching { ctx.getSystemService(ConnectivityManager::class.java)?.let(::lanNetwork)?.bindSocket(s) }
}

/** OkHttp's sockets, opened on [network] when it names one (see [lanNetwork]). */
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
