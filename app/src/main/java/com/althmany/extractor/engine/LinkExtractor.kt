package com.althmany.extractor.engine

import com.althmany.extractor.data.LinkCategory
import java.net.URI
import java.util.Locale

object LinkExtractor {
    private val urlRegex = Regex(
        pattern = "(?i)\\b((?:https?://|www\\.)[^\\s<>\\[\\]{}()\\\"']+)",
        options = setOf(RegexOption.IGNORE_CASE)
    )

    fun extract(text: CharSequence?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return urlRegex.findAll(text)
            .map { sanitize(it.groupValues[1]) }
            .filter { it.length >= 8 }
            .distinct()
            .toList()
    }

    fun normalize(raw: String): String {
        val sanitized = sanitize(raw)
        val withScheme = if (sanitized.startsWith("www.", ignoreCase = true)) {
            "https://$sanitized"
        } else sanitized

        return runCatching {
            val uri = URI(withScheme)
            val scheme = (uri.scheme ?: "https").lowercase(Locale.ROOT)
            val host = (uri.host ?: uri.authority ?: "").lowercase(Locale.ROOT).removePrefix("www.")
            val path = (uri.rawPath ?: "").let { if (it.length > 1) it.trimEnd('/') else it }
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            val fragmentless = "$scheme://$host$path$query"
            fragmentless.trimEnd('/')
        }.getOrElse {
            withScheme.substringBefore('#').trimEnd('/')
        }
    }

    fun category(raw: String): LinkCategory {
        val normalized = normalize(raw)
        val lower = normalized.lowercase(Locale.ROOT)
        val host = runCatching { URI(normalized).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.") }.getOrDefault("")
        return when {
            lower.substringBefore('?').endsWith(".pdf") -> LinkCategory.PDF
            host == "chat.whatsapp.com" || host.endsWith(".whatsapp.com") || host == "wa.me" -> LinkCategory.WHATSAPP
            host == "t.me" || host == "telegram.me" || host.endsWith(".telegram.org") -> LinkCategory.TELEGRAM
            host == "instagram.com" || host.endsWith(".instagram.com") -> LinkCategory.INSTAGRAM
            host == "facebook.com" || host.endsWith(".facebook.com") || host == "fb.watch" -> LinkCategory.FACEBOOK
            host == "google.com" || host.endsWith(".google.com") || host == "goo.gl" || host == "forms.gle" -> LinkCategory.GOOGLE
            else -> LinkCategory.OTHER
        }
    }

    private fun sanitize(value: String): String {
        return value.trim()
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&nbsp;", "", ignoreCase = true)
            .replace("\u200B", "")
            .replace("\u200E", "")
            .replace("\u200F", "")
            .replace("\u2060", "")
            .trimEnd('.', ',', ';', ':', '!', '?', '،', '؛', '。')
            .trimEnd(')', ']', '}', '»', '”', '\'', '"')
    }
}
