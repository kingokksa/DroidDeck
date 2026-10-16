package com.droiddeck.launcher.core

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

object AppLanguage {
    const val SYSTEM = ""

    val supported: List<String> = listOf(
        "en", "id", "ms", "cs", "da", "de", "es", "es-419", "fr", "it", "hu", "nl", "nb", "pl",
        "pt-BR", "pt-PT", "ro", "fi", "sv", "vi", "tr", "el", "bg", "ru", "uk", "ar", "hi", "th",
        "ja", "ko", "zh-CN", "zh-TW", "zh-HK",
    )

    private const val PREFS = "language"
    private const val KEY = "app_language"

    @Volatile private var systemLocale: Locale? = null

    fun chosen(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM)
            ?.takeIf { it in supported } ?: SYSTEM

    fun setChosen(context: Context, tag: String) {
        val value = if (tag in supported) tag else SYSTEM
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).commit()
        val locale = value.takeIf { it.isNotEmpty() }?.let(Locale::forLanguageTag) ?: system(context)
        Locale.setDefault(locale)
        val app = context.applicationContext
        @Suppress("DEPRECATION")
        app.resources.updateConfiguration(
            Configuration(app.resources.configuration).apply { setLocales(LocaleList(locale)) },
            app.resources.displayMetrics,
        )
    }

    fun system(context: Context): Locale =
        systemLocale ?: context.resources.configuration.locales[0] ?: Locale.getDefault()

    fun effective(context: Context): Locale =
        chosen(context).takeIf { it.isNotEmpty() }?.let(Locale::forLanguageTag) ?: system(context)

    fun noteSystem(base: Context) {
        if (systemLocale == null) systemLocale = base.resources.configuration.locales[0]
    }

    fun wrap(base: Context): Context {
        noteSystem(base)
        val tag = chosen(base).ifEmpty { return base }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        return base.createConfigurationContext(override(locale))
    }

    fun applyTo(activity: Activity, base: Context) {
        noteSystem(base)
        val tag = chosen(base).ifEmpty { return }
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        activity.applyOverrideConfiguration(override(locale))
    }

    fun systemChanged(config: Configuration) {
        config.locales[0]?.let { systemLocale = it }
    }

    private fun override(locale: Locale) = Configuration().apply { fontScale = 0f; setLocales(LocaleList(locale)) }

    private val latinAmerica = setOf(
        "419", "AR", "BO", "BR", "BZ", "CL", "CO", "CR", "CU", "DO", "EC", "GT", "HN", "MX", "NI", "PA",
        "PE", "PR", "PY", "SV", "US", "UY", "VE",
    )

    fun supportedTag(locale: Locale): String? = when (locale.language) {
        "en", "fr", "ru", "ja", "ko", "ms", "cs", "da", "de", "it", "hu", "nl", "nb", "pl", "ro", "fi",
        "sv", "vi", "tr", "el", "bg", "uk", "ar", "hi", "th" -> locale.language
        "in", "id" -> "id"
        "es" -> if (locale.country in latinAmerica) "es-419" else "es"
        "pt" -> if (locale.country.isEmpty() || locale.country == "BR") "pt-BR" else "pt-PT"
        "zh" -> when {
            locale.script == "Hans" -> "zh-CN"
            locale.country == "HK" || locale.country == "MO" -> "zh-HK"
            locale.script == "Hant" || locale.country == "TW" -> "zh-TW"
            else -> "zh-CN"
        }
        else -> null
    }

    fun displayName(locale: Locale): String =
        supportedTag(locale)?.let(::nativeName) ?: locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }

    fun nativeName(tag: String): String = when (tag) {
        "en" -> "English"
        "id" -> "Bahasa Indonesia"
        "ms" -> "Bahasa Melayu"
        "cs" -> "Čeština"
        "da" -> "Dansk"
        "de" -> "Deutsch"
        "es" -> "Español (España)"
        "es-419" -> "Español (Latinoamérica)"
        "fr" -> "Français"
        "it" -> "Italiano"
        "hu" -> "Magyar"
        "nl" -> "Nederlands"
        "nb" -> "Norsk bokmål"
        "pl" -> "Polski"
        "pt-BR" -> "Português (Brasil)"
        "pt-PT" -> "Português (Portugal)"
        "ro" -> "Română"
        "fi" -> "Suomi"
        "sv" -> "Svenska"
        "vi" -> "Tiếng Việt"
        "tr" -> "Türkçe"
        "el" -> "Ελληνικά"
        "bg" -> "Български"
        "ru" -> "Русский"
        "uk" -> "Українська"
        "ar" -> "العربية"
        "hi" -> "हिन्दी"
        "th" -> "ไทย"
        "ja" -> "日本語"
        "ko" -> "한국어"
        "zh-CN" -> "简体中文"
        "zh-TW" -> "繁體中文（台灣）"
        "zh-HK" -> "繁體中文（香港）"
        else -> Locale.forLanguageTag(tag).let { it.getDisplayName(it) }
    }

    fun selectedTag(context: Context): String = supportedTag(effective(context)) ?: ""

    fun desktopTag(tag: String): String = when (tag) {
        "es-419" -> "es"
        "pt-PT" -> "pt"
        "zh-CN" -> "zh_CN"
        "zh-TW" -> "zh_TW"
        "zh-HK" -> "zh_TW"
        else -> tag.replace('-', '_')
    }

    fun linuxLocale(tag: String): String? = linuxLocales[tag]

    private val linuxLocales = mapOf(
        "id" to "id_ID.UTF-8", "ms" to "ms_MY.UTF-8", "cs" to "cs_CZ.UTF-8",
        "da" to "da_DK.UTF-8", "de" to "de_DE.UTF-8", "es" to "es_ES.UTF-8", "es-419" to "es_MX.UTF-8",
        "fr" to "fr_FR.UTF-8", "it" to "it_IT.UTF-8", "hu" to "hu_HU.UTF-8", "nl" to "nl_NL.UTF-8",
        "nb" to "nb_NO.UTF-8", "pl" to "pl_PL.UTF-8", "pt-BR" to "pt_BR.UTF-8", "pt-PT" to "pt_PT.UTF-8",
        "ro" to "ro_RO.UTF-8", "fi" to "fi_FI.UTF-8", "sv" to "sv_SE.UTF-8", "vi" to "vi_VN.UTF-8",
        "tr" to "tr_TR.UTF-8", "el" to "el_GR.UTF-8", "bg" to "bg_BG.UTF-8", "ru" to "ru_RU.UTF-8",
        "uk" to "uk_UA.UTF-8", "ar" to "ar_EG.UTF-8", "hi" to "hi_IN.UTF-8", "th" to "th_TH.UTF-8",
        "ja" to "ja_JP.UTF-8", "ko" to "ko_KR.UTF-8", "zh-CN" to "zh_CN.UTF-8", "zh-TW" to "zh_TW.UTF-8",
        "zh-HK" to "zh_HK.UTF-8",
    )
}
