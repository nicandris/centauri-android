package app.centauri.remote

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.net.InetAddress
import java.net.ServerSocket

/** Which network LAN traffic goes out on, and the sockets opened there. */
@RunWith(RobolectricTestRunner::class)
class LanTest {
    private fun pick(active: String?, vararg networks: Pair<String, Int>) =
        pickLan(active, networks.map { it.first }) { n, t -> networks.any { it.first == n && it.second == t } }

    @Test
    fun wifiIsUsedOnlyWhenTheDefaultRouteWouldMissTheLan() {
        assertEquals("wifi", pick("mobile", "mobile" to TRANSPORT_CELLULAR, "wifi" to TRANSPORT_WIFI))
        assertEquals("eth", pick("mobile", "mobile" to TRANSPORT_CELLULAR, "eth" to TRANSPORT_ETHERNET))
        assertEquals("wifi", pick(null, "wifi" to TRANSPORT_WIFI)) // no default network at all
        assertNull(pick("wifi", "wifi" to TRANSPORT_WIFI)) // the default already is the LAN
        assertNull(pick("eth", "eth" to TRANSPORT_ETHERNET))
        // A VPN is the user's route to the printer (away from home): leave it alone.
        assertNull(pick("vpn", "vpn" to TRANSPORT_VPN, "wifi" to TRANSPORT_WIFI))
        assertNull(pick("mobile", "mobile" to TRANSPORT_CELLULAR)) // nothing better to offer
    }

    private fun caps(transport: Int): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also { shadowOf(it).addTransportType(transport) }

    @Test
    fun theSystemsWifiNetworkIsPickedOverMobileData() {
        val app = RuntimeEnvironment.getApplication()
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork!!
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.first { it != active }
        shadowOf(cm).setNetworkCapabilities(active, caps(TRANSPORT_CELLULAR))
        shadowOf(cm).setNetworkCapabilities(wifi, caps(TRANSPORT_WIFI))
        assertEquals(wifi, lanNetwork(cm))
        lanDatagramSocket(app).use { assertFalse(it.isClosed) } // binding is best effort: a socket either way
    }

    @Test
    fun withoutALanNetworkSocketsUseTheDefaultRoute() {
        val factory = LanSocketFactory { null }
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            val host = server.inetAddress
            val port = server.localPort
            val local = InetAddress.getLoopbackAddress()
            listOf(
                factory.createSocket().apply { connect(java.net.InetSocketAddress(host, port)) },
                factory.createSocket(host.hostAddress!!, port),
                factory.createSocket(host.hostAddress!!, port, local, 0),
                factory.createSocket(host, port),
                factory.createSocket(host, port, local, 0),
            ).forEach { it.use { s -> assertTrue(s.isConnected) } }
        }
    }

    @Test
    fun onlyDottedIpv4AddressesAreTried() {
        assertTrue(isIpv4("192.168.1.40"))
        assertTrue(isIpv4("10.0.0.255"))
        listOf("", "192.168.1", "192.168.1.256", "192.168.1.40.1", "printer.local", "192.168..40", "1234.1.1.1", "-1.2.3.4")
            .forEach { assertFalse(it, isIpv4(it)) }
    }
}
