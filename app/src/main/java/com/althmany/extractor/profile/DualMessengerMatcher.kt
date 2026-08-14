package com.althmany.extractor.profile

import java.util.Locale

object DualMessengerMatcher {
    fun isWhatsApp(label: CharSequence?): Boolean {
        val v = normalize(label)
        return v.contains("whatsapp") || v.contains("واتساب")
    }

    fun isExplicitDualMessenger(label: CharSequence?): Boolean {
        val v = normalize(label)
        return isWhatsApp(v) && (
            v.contains("dual messenger") ||
            v.contains("المراسل المزدوج") ||
            v.contains("واتساب المزدوج") ||
            v.contains("نسخة واتساب") ||
            v.contains("cloned whatsapp") ||
            v.contains("secondary whatsapp")
        )
    }

    private fun normalize(label: CharSequence?): String = label
        ?.toString()?.trim()?.replace("ـ", "")
        ?.replace(Regex("[\u064B-\u065F\u0670]"), "")
        ?.replace(Regex("\\s+"), " ")
        ?.lowercase(Locale.ROOT).orEmpty()
}
