package com.althmany.extractor.engine

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

    private fun sanitize(value: String): String {
        return value.trim()
            .trimEnd('.', ',', ';', ':', '!', '?', '،', '؛', '。')
            .trimEnd(')', ']', '}', '»', '”', '\'', '"')
    }
}
