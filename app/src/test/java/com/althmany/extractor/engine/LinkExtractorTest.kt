package com.althmany.extractor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkExtractorTest {
    @Test
    fun extractsArabicAdjacentUrlsAndTrimsPunctuation() {
        val text = "للتسجيل: https://example.com/job/123، ثم https://t.me/jobs_yemen."
        val urls = LinkExtractor.extract(text)
        assertEquals(2, urls.size)
        assertTrue(urls.contains("https://example.com/job/123"))
        assertTrue(urls.contains("https://t.me/jobs_yemen"))
    }

    @Test
    fun normalizesHostAndTrailingSlash() {
        assertEquals(
            "https://example.com/path",
            LinkExtractor.normalize("HTTPS://WWW.Example.com/path/")
        )
    }
}
