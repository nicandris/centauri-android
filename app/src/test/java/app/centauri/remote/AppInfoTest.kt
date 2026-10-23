package app.centauri.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
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
}
