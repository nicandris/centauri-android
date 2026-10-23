package app.centauri.remote

import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class) // for the phone's network links
class DiscoveryTest {
    @Test
    fun aPrinterAnswersTheProbe() {
        val printer = DatagramSocket(0, InetAddress.getLoopbackAddress()) // any free port
        thread {
            val buf = ByteArray(64)
            val probe = DatagramPacket(buf, buf.size).also(printer::receive)
            val reply = """{"Id":"c1","Data":{"Name":"Shop","MainboardID":"m1"}}""".toByteArray()
            printer.send(DatagramPacket(reply, reply.size, probe.socketAddress))
        }
        val found = printer.use { runBlocking { discover(listOf("no-such-host.invalid", "127.0.0.1"), port = printer.localPort) } }
        assertEquals(listOf(Printer("Shop", "127.0.0.1", "m1", "c1")), found)
    }

    @Test
    fun aDroppedFirstProbeIsSentAgainAndATypedInIpReturnsAtTheFirstReply() {
        val printer = DatagramSocket(0, InetAddress.getLoopbackAddress()) // any free port
        thread {
            val buf = ByteArray(64)
            DatagramPacket(buf, buf.size).also(printer::receive) // lost to power save
            val probe = DatagramPacket(buf, buf.size).also(printer::receive)
            val reply = """{"Id":"c1","Data":{"Name":"Shop","MainboardID":"m1"}}""".toByteArray()
            printer.send(DatagramPacket(reply, reply.size, probe.socketAddress))
        }
        val started = System.nanoTime()
        val found = printer.use { runBlocking { discover(listOf("127.0.0.1"), untilFirst = true, windowMs = 9_000, port = printer.localPort) } }
        assertEquals(listOf("m1"), found.map { it.mainboardId })
        // The second burst goes out a third of the way in; the window's end isn't waited for.
        assertTrue((System.nanoTime() - started) / 1_000_000 < 6_000)
    }

    @Test
    fun cancellingStopsTheSearch() = runBlocking {
        val search = async { discover(listOf("127.0.0.1")) }
        delay(300)
        search.cancel()
        assertTrue(search.isCancelled)
    }

    @Test
    fun noSocketMeansNoPrintersNotACrash() {
        val found = runBlocking { discover(listOf("127.0.0.1")) { throw SocketException("no network") } }
        assertEquals(emptyList<Printer>(), found)
    }

    @Test
    fun broadcastsOnEveryIpv4Link() {
        val cm = RuntimeEnvironment.getApplication().getSystemService(ConnectivityManager::class.java)
        // The phone's default network is the Wi-Fi (the mobile-data case is in LanTest).
        val wifi = ShadowNetworkCapabilities.newInstance().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) }
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, wifi)
        assertEquals(listOf("255.255.255.255"), broadcastTargets(RuntimeEnvironment.getApplication()))
        // Building link addresses is a system API; reflection reaches it in the test framework.
        val la = LinkAddress::class.java.getConstructor(InetAddress::class.java, Int::class.javaPrimitiveType)
        val lp = LinkProperties()
        LinkProperties::class.java.getMethod("setLinkAddresses", Collection::class.java).invoke(lp, listOf(
            la.newInstance(InetAddress.getByName("192.168.178.64"), 24),
            la.newInstance(InetAddress.getByName("fe80::1"), 64),
        ))
        shadowOf(cm).setLinkProperties(cm.activeNetwork, lp)
        assertEquals(listOf("192.168.178.255", "255.255.255.255"), broadcastTargets(RuntimeEnvironment.getApplication()))
    }
}
