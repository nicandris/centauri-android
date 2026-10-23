package app.centauri.remote

import android.content.Context
import android.content.res.Configuration
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Locale

const val REPO_URL = "https://github.com/nicandris/centauri-android"
const val RELEASES_URL = "$REPO_URL/releases"

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

/** [base] showing [tag]'s translations (Android 12 and older, which have no per-app language); [base] itself for "". */
fun withLanguage(base: Context, tag: String): Context =
    if (tag.isEmpty()) base
    else base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
