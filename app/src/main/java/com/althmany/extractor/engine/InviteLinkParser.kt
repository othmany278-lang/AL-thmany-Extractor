package com.althmany.extractor.engine

import java.net.URI
import java.util.Locale

object InviteLinkParser {
    private val urlRegex = Regex("https?://chat\\.whatsapp\\.com/[A-Za-z0-9_-]+(?:[/?#][^\\s]*)?", RegexOption.IGNORE_CASE)

    data class Invite(val originalUrl: String, val normalizedUrl: String, val code: String)

    fun extract(text: CharSequence?): List<Invite> {
        if (text.isNullOrBlank()) return emptyList()
        return urlRegex.findAll(text).mapNotNull { parse(it.value) }.distinctBy { it.code.lowercase(Locale.ROOT) }.toList()
    }

    fun parse(raw: String): Invite? = runCatching {
        val cleaned = raw.trim().trimEnd('.', ',', '،', ';', ':', ')', ']', '}', '>', '"', '\'')
        val uri = URI(cleaned)
        if (!uri.host.equals("chat.whatsapp.com", ignoreCase = true)) return null
        val code = uri.path.orEmpty().trim('/').substringBefore('/').trim()
        if (code.length < 8 || !code.matches(Regex("[A-Za-z0-9_-]+"))) return null
        Invite(
            originalUrl = cleaned,
            normalizedUrl = "https://chat.whatsapp.com/$code",
            code = code
        )
    }.getOrNull()
}
