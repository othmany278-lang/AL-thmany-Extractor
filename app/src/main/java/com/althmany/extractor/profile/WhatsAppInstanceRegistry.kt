package com.althmany.extractor.profile

import android.content.Context
import android.content.pm.PackageManager

/** A WhatsApp installation visible to the current Android profile only. */
data class WhatsAppInstance(
    val packageName: String,
    val labelAr: String,
    val installed: Boolean,
    val launchable: Boolean
)

object WhatsAppInstanceRegistry {
    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"

    private val known = listOf(
        WHATSAPP to "واتساب",
        WHATSAPP_BUSINESS to "واتساب للأعمال"
    )

    fun available(context: Context): List<WhatsAppInstance> {
        val pm = context.packageManager
        return known.map { (pkg, label) ->
            val installed = packageInstalled(pm, pkg)
            WhatsAppInstance(
                packageName = pkg,
                labelAr = label,
                installed = installed,
                launchable = installed && pm.getLaunchIntentForPackage(pkg) != null
            )
        }.filter { it.installed }
    }

    fun launchable(context: Context): List<WhatsAppInstance> = available(context).filter { it.launchable }

    fun labelFor(packageName: String?): String = when (packageName) {
        WHATSAPP -> "واتساب"
        WHATSAPP_BUSINESS -> "واتساب للأعمال"
        null -> "غير محدد"
        else -> packageName
    }

    fun isSupportedPackage(packageName: String?): Boolean = packageName == WHATSAPP || packageName == WHATSAPP_BUSINESS

    private fun packageInstalled(pm: PackageManager, packageName: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        pm.getApplicationInfo(packageName, 0)
        true
    }.getOrDefault(false)
}
