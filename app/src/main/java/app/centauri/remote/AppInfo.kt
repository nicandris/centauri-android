package app.centauri.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

const val REPO_URL = "https://github.com/nicandris/centauri-android"
const val RELEASES_URL = "$REPO_URL/releases"
const val LATEST_RELEASE_URL = "https://api.github.com/repos/nicandris/centauri-android/releases/latest"
/** GitHub's release JSON is a few KB (more with long notes); anything past this isn't one. */
private const val MAX_RELEASE_BYTES = 256L * 1024

/** Dotted numbers compared part by part (0.10.0 > 0.9.1, 1.0 == 1.0.0); null when either isn't one. A leading "v" is fine. */
fun compareVersions(a: String, b: String): Int? {
    fun parts(v: String): List<Int>? = v.removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }
    val x = parts(a) ?: return null
    val y = parts(b) ?: return null
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
        if (d != 0) return d
    }
    return 0
}

/** What "Check for updates" found. */
sealed interface UpdateCheck {
    data object Latest : UpdateCheck
    /** [page] is the release on github.com; null when GitHub named another host. */
    data class Available(val version: String, val page: String?) : UpdateCheck
    data object Failed : UpdateCheck
}

/**
 * Asks GitHub for the latest release, only when the user taps "Check for updates". [http] is [Hub.http]: this
 * call goes to the internet, so it gets the default socket factory (the LAN one may pick a Wi-Fi without
 * internet), follows redirects and gives up quickly.
 */
suspend fun checkForUpdate(http: OkHttpClient, current: String, url: String): UpdateCheck {
    val internet = http.newBuilder().socketFactory(SocketFactory.getDefault())
        .followRedirects(true).followSslRedirects(true)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    val release = orNull { JSONObject(String(internet.fetchBytes(url, MAX_RELEASE_BYTES, "application/vnd.github+json"))) }
        ?: return UpdateCheck.Failed
    val tag = release.optString("tag_name").removePrefix("v")
    val newer = compareVersions(tag, current) ?: return UpdateCheck.Failed
    if (newer <= 0) return UpdateCheck.Latest
    val page = release.optString("html_url").toHttpUrlOrNull()?.takeIf { it.isHttps && it.host == "github.com" }
    return UpdateCheck.Available(tag, page?.toString())
}

/** A new GitHub issue titled [title], its body prefilled with the firmware and app version and nothing personal. */
fun issueUrl(title: String, firmware: String, appVersion: String): String =
    "$REPO_URL/issues/new".toHttpUrl().newBuilder()
        .addQueryParameter("title", title)
        .addQueryParameter("body", "Firmware: ${firmware.ifEmpty { "unknown" }}\nApp version: $appVersion\n\nWhat happened:\n")
        .build().toString()

/** The in-app language choices; "" follows the phone. */
val LANGUAGES = listOf("", "en", "nl", "el")

/** Which of [LANGUAGES] a locale list ("nl-NL,en") picks; "" when it's empty or not one we ship. */
fun languageChoice(tags: String): String =
    tags.substringBefore(',').substringBefore('-').lowercase().takeIf { it in LANGUAGES }.orEmpty()
