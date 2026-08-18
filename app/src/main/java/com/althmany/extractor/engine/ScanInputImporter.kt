package com.althmany.extractor.engine

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

object ScanInputImporter {
    private const val MAX_FILE_BYTES = 64 * 1024 * 1024
    private const val MAX_ZIP_ENTRY_BYTES = 12 * 1024 * 1024

    fun readLinks(resolver: ContentResolver, uri: Uri): Set<String> {
        val name = displayName(resolver, uri).lowercase()
        val mime = resolver.getType(uri).orEmpty().lowercase()
        return resolver.openInputStream(uri)?.use { input ->
            when {
                name.endsWith(".xlsx") || mime.contains("spreadsheetml") -> readXlsx(input)
                name.endsWith(".xls") || mime.contains("ms-excel") -> readLegacyXls(input)
                else -> readTextFile(input)
            }
        } ?: emptySet()
    }

    private fun readXlsx(input: InputStream): Set<String> {
        val out = linkedSetOf<String>()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val n = entry.name.lowercase()
                if (!n.startsWith("xl/")) continue
                if (!(n.endsWith(".xml") || n.endsWith(".rels"))) continue
                val bytes = readLimited(zip, MAX_ZIP_ENTRY_BYTES)
                val text = bytes.toString(Charsets.UTF_8)
                    .replace("&amp;", "&")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                InviteLinkParser.extract(text).forEach { out += it.normalizedUrl }
            }
        }
        return out
    }

    private fun readLegacyXls(input: InputStream): Set<String> {
        val bytes = readLimited(input, MAX_FILE_BYTES)
        val out = linkedSetOf<String>()
        InviteLinkParser.extract(bytes.toString(Charsets.ISO_8859_1)).forEach { out += it.normalizedUrl }
        if (bytes.size >= 2) {
            runCatching { bytes.toString(Charsets.UTF_16LE) }
                .getOrNull()
                ?.let(InviteLinkParser::extract)
                ?.forEach { out += it.normalizedUrl }
        }
        return out
    }

    private fun readTextFile(input: InputStream): Set<String> {
        val bytes = readLimited(input, MAX_FILE_BYTES)
        return InviteLinkParser.extract(bytes.toString(Charsets.UTF_8))
            .mapTo(linkedSetOf()) { it.normalizedUrl }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0).orEmpty() else ""
            }.orEmpty()
        }.getOrDefault("")

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(limit, 256 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            val allowed = minOf(n, limit - total)
            if (allowed > 0) out.write(buffer, 0, allowed)
            total += allowed
            if (total >= limit) break
        }
        return out.toByteArray()
    }
}
