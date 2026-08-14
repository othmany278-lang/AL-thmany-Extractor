package com.althmany.extractor.profile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock

enum class WhatsAppInstanceKind { PERSONAL, BUSINESS, CLONED, DISCOVERED }

data class WhatsAppInstance(
    val packageName: String,
    val labelAr: String,
    val installed: Boolean,
    val launchable: Boolean,
    val kind: WhatsAppInstanceKind = WhatsAppInstanceKind.DISCOVERED,
    val official: Boolean = false,
    val canHandleInvite: Boolean = false,
    val profileKey: String = ""
)

object WhatsAppInstanceRegistry {
    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    const val WHATSAPP_CLONED = "com.whatsapp2"
    private const val SAMPLE_INVITE = "https://chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv"
    private const val CACHE_MS = 5_000L

    @Volatile private var cacheAt = 0L
    @Volatile private var cacheProfile = ""
    @Volatile private var cache: List<WhatsAppInstance> = emptyList()

    fun available(context: Context, forceRefresh: Boolean = false): List<WhatsAppInstance> {
        val profile = RuntimeProfileDetector.detect(context)
        val now = SystemClock.elapsedRealtime()
        if (!forceRefresh && cacheProfile == profile.profileKey && now - cacheAt < CACHE_MS) return cache

        val pm = context.packageManager
        val byPackage = linkedMapOf<String, WhatsAppInstance>()

        fun addPackage(pkg: String, label: String? = null, kind: WhatsAppInstanceKind = kindFor(pkg)) {
            if (!packageInstalled(pm, pkg)) return
            val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
            val resolvedLabel = label ?: appInfo?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() } ?: labelFor(pkg)
            val launchable = pm.getLaunchIntentForPackage(pkg) != null
            val invite = canHandleInvite(context, pkg)
            byPackage[pkg] = WhatsAppInstance(
                packageName = pkg, labelAr = friendlyLabel(pkg, resolvedLabel), installed = true,
                launchable = launchable, kind = kind, official = pkg == WHATSAPP || pkg == WHATSAPP_BUSINESS,
                canHandleInvite = invite, profileKey = profile.profileKey
            )
        }

        listOf(WHATSAPP, WHATSAPP_BUSINESS, WHATSAPP_CLONED).forEach { addPackage(it) }

        val launcher = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        runCatching { pm.queryIntentActivities(launcher, 0) }.getOrDefault(emptyList()).forEach { ri ->
            val pkg = ri.activityInfo?.packageName.orEmpty()
            val label = runCatching { ri.loadLabel(pm)?.toString().orEmpty() }.getOrDefault("")
            if (looksLikeWhatsApp(pkg, label)) addPackage(pkg, label)
        }

        val inviteIntent = Intent(Intent.ACTION_VIEW, Uri.parse(SAMPLE_INVITE)).apply { addCategory(Intent.CATEGORY_BROWSABLE) }
        runCatching { pm.queryIntentActivities(inviteIntent, 0) }.getOrDefault(emptyList()).forEach { ri ->
            val pkg = ri.activityInfo?.packageName.orEmpty()
            val label = runCatching { ri.loadLabel(pm)?.toString().orEmpty() }.getOrDefault("")
            if (looksLikeWhatsApp(pkg, label)) addPackage(pkg, label)
        }

        val result = byPackage.values.sortedWith(compareBy<WhatsAppInstance>({ it.kind.ordinal }, { it.labelAr.lowercase() }, { it.packageName }))
        cache = result; cacheProfile = profile.profileKey; cacheAt = now
        return result
    }

    fun launchable(context: Context, forceRefresh: Boolean = false): List<WhatsAppInstance> =
        available(context, forceRefresh).filter { it.launchable }

    fun labelFor(packageName: String?): String = when (packageName) {
        WHATSAPP -> "واتساب الشخصي"
        WHATSAPP_BUSINESS -> "واتساب للأعمال"
        WHATSAPP_CLONED -> "واتساب Dual/Clone"
        null -> "غير محدد"
        else -> cache.firstOrNull { it.packageName == packageName }?.labelAr ?: packageName
    }

    /**
     * Accessibility config is intentionally not package-filtered in 2.15 so vendor clone packages
     * can be discovered at runtime. We still ignore unrelated apps here.
     */
    fun isSupportedPackage(packageName: String?): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isBlank()) return false
        if (pkg == WHATSAPP || pkg == WHATSAPP_BUSINESS || pkg == WHATSAPP_CLONED) return true
        if (cache.any { it.packageName == pkg }) return true
        return pkg.contains("whatsapp", ignoreCase = true)
    }

    fun canHandleInvite(context: Context, packageName: String): Boolean = runCatching {
        Intent(Intent.ACTION_VIEW, Uri.parse(SAMPLE_INVITE)).apply {
            setPackage(packageName); addCategory(Intent.CATEGORY_BROWSABLE)
        }.resolveActivity(context.packageManager) != null
    }.getOrDefault(false)

    fun supportsDualMessenger(context: Context): Boolean =
        packageInstalled(context.packageManager, WHATSAPP_CLONED) ||
            (RuntimeProfileDetector.detect(context).samsungDevice && packageInstalled(context.packageManager, WHATSAPP))

    private fun packageInstalled(pm: PackageManager, packageName: String): Boolean = runCatching {
        @Suppress("DEPRECATION") pm.getApplicationInfo(packageName, 0); true
    }.getOrDefault(false)

    private fun looksLikeWhatsApp(pkg: String, label: String): Boolean {
        val p = pkg.lowercase(); val l = label.lowercase()
        return pkg in setOf(WHATSAPP, WHATSAPP_BUSINESS, WHATSAPP_CLONED) || "whatsapp" in p || "whatsapp" in l || "واتساب" in l
    }

    private fun kindFor(pkg: String) = when (pkg) {
        WHATSAPP -> WhatsAppInstanceKind.PERSONAL
        WHATSAPP_BUSINESS -> WhatsAppInstanceKind.BUSINESS
        WHATSAPP_CLONED -> WhatsAppInstanceKind.CLONED
        else -> WhatsAppInstanceKind.DISCOVERED
    }

    private fun friendlyLabel(pkg: String, raw: String): String = when (kindFor(pkg)) {
        WhatsAppInstanceKind.PERSONAL -> "واتساب الشخصي"
        WhatsAppInstanceKind.BUSINESS -> "واتساب للأعمال"
        WhatsAppInstanceKind.CLONED -> "واتساب Dual/Clone"
        WhatsAppInstanceKind.DISCOVERED -> raw.ifBlank { pkg }
    }
}
