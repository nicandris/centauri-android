package app.centauri.remote

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Links out of the app, and the in-app language. */
@RunWith(RobolectricTestRunner::class)
class AppInfoTest {
    @Test
    fun anIssueIsPrefilledWithFirmwareAndVersionOnly() {
        val url = issueUrl("[Lab] Timelapses & more", "V1.1.46", "0.8.1").toHttpUrl()
        assertEquals("https://github.com/nicandris/centauri-android/issues/new", url.newBuilder().query(null).build().toString())
        assertEquals("[Lab] Timelapses & more", url.queryParameter("title"))
        assertEquals("Firmware: V1.1.46\nApp version: 0.8.1\n\nWhat happened:\n", url.queryParameter("body"))
        assertTrue(issueUrl("x", "", "1").toHttpUrl().queryParameter("body")!!.startsWith("Firmware: unknown\n"))
        assertTrue(!issueUrl("a b", "", "1").contains(' '))
    }

    @Test
    fun theLanguageChoiceIsOneWeShip() {
        assertEquals("nl", languageChoice("nl-NL,en-GB"))
        assertEquals("el", languageChoice("el"))
        assertEquals("", languageChoice(""))
        assertEquals("", languageChoice("ja-JP"))
    }

    @Test
    fun olderAndroidsGetTheChosenTranslations() {
        val app = RuntimeEnvironment.getApplication()
        assertSame(app, withLanguage(app, ""))
        assertEquals("Annuleren", withLanguage(app, "nl").getString(R.string.cancel))
        assertEquals("Άκυρο", withLanguage(app, "el").getString(R.string.cancel))
    }

    @Test
    fun versionsCompareByNumber() {
        assertEquals(1, compareVersions("0.10.0", "0.9.1"))
        assertEquals(-1, compareVersions("0.8.1", "v0.9.0"))
        assertEquals(0, compareVersions("1.0", "1.0.0"))
        assertEquals(1, compareVersions("1.0.1", "1.0"))
        assertNull(compareVersions("latest", "0.8.1"))
        assertNull(compareVersions("0.8.1", "0.8.1-beta"))
        assertNull(compareVersions("", "0.8.1"))
    }

    private fun release(tag: String, page: String = "https://github.com/nicandris/centauri-android/releases/tag/$tag") =
        """{"tag_name":"$tag","html_url":"$page"}"""

    @Test
    fun theUpdateCheckReadsGitHubsLatestRelease() = runBlocking {
        val server = MockWebServer().apply { start() }
        // Hub.http's LAN-only socket factory and no-redirect policy must not carry over.
        val lanOnly = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .socketFactory(object : javax.net.SocketFactory() {
                override fun createSocket(): java.net.Socket = throw java.io.IOException("LAN only")
                override fun createSocket(h: String, p: Int) = createSocket()
                override fun createSocket(h: String, p: Int, l: java.net.InetAddress, lp: Int) = createSocket()
                override fun createSocket(h: java.net.InetAddress, p: Int) = createSocket()
                override fun createSocket(h: java.net.InetAddress, p: Int, l: java.net.InetAddress, lp: Int) = createSocket()
            }).build()
        val url = server.url("/releases/latest").toString()
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", server.url("/moved").toString()).build())
        server.enqueue(MockResponse(body = release("v0.9.0")))
        assertEquals(
            UpdateCheck.Available("0.9.0", "https://github.com/nicandris/centauri-android/releases/tag/v0.9.0"),
            checkForUpdate(lanOnly, "0.8.1", url),
        )
        assertEquals("application/vnd.github+json", server.takeRequest().headers["Accept"])

        server.enqueue(MockResponse(body = release("v0.8.1")))
        assertEquals(UpdateCheck.Latest, checkForUpdate(lanOnly, "0.8.1", url))
        server.enqueue(MockResponse(body = release("0.7.0")))
        assertEquals(UpdateCheck.Latest, checkForUpdate(lanOnly, "0.8.1", url))
        // Only a github.com page is offered.
        server.enqueue(MockResponse(body = release("1.0.0", page = "https://example.com/x")))
        assertEquals(UpdateCheck.Available("1.0.0", null), checkForUpdate(lanOnly, "0.8.1", url))
        server.enqueue(MockResponse(body = release("1.0.0", page = "http://github.com/x")))
        assertEquals(UpdateCheck.Available("1.0.0", null), checkForUpdate(lanOnly, "0.8.1", url))
        // A garbage tag, an error, a body that isn't JSON or is too big: "couldn't check".
        server.enqueue(MockResponse(body = release("nightly")))
        assertEquals(UpdateCheck.Failed, checkForUpdate(lanOnly, "0.8.1", url))
        server.enqueue(MockResponse(code = 500))
        assertEquals(UpdateCheck.Failed, checkForUpdate(lanOnly, "0.8.1", url))
        server.enqueue(MockResponse(body = "<html>"))
        assertEquals(UpdateCheck.Failed, checkForUpdate(lanOnly, "0.8.1", url))
        server.enqueue(MockResponse(body = release("v9.0.0") + " ".repeat(300 * 1024)))
        assertEquals(UpdateCheck.Failed, checkForUpdate(lanOnly, "0.8.1", url))
        server.close()
        assertEquals(LATEST_RELEASE_URL, "https://api.github.com/repos/nicandris/centauri-android/releases/latest")
    }
}
