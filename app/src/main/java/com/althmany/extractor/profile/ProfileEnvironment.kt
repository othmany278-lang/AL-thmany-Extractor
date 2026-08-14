package com.althmany.extractor.profile

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.UserManager

/** Profile-local runtime identity inspired by the proven 2.8.0 profile bridge. */
enum class RuntimeProfileKind {
    PERSONAL,
    WORK,
    SAMSUNG_ISOLATED,
    SECONDARY,
    UNKNOWN
}

data class RuntimeProfileInfo(
    val kind: RuntimeProfileKind,
    val labelAr: String,
    val detailAr: String,
    val isManagedProfile: Boolean,
    val isProfile: Boolean,
    val isSystemUser: Boolean,
    val profileHandle: String = "",
    val profileKey: String = "UNKNOWN",
    val userSerial: Long = -1L,
    val samsungDevice: Boolean = false
) {
    val isIsolated: Boolean get() = kind != RuntimeProfileKind.PERSONAL
    val secondaryProfile: Boolean get() = isManagedProfile || !isSystemUser
    val isLikelySecureFolder: Boolean get() = kind == RuntimeProfileKind.SAMSUNG_ISOLATED
    val requiresExplicitTarget: Boolean get() = secondaryProfile || isLikelySecureFolder

    companion object {
        fun unknown() = RuntimeProfileInfo(
            kind = RuntimeProfileKind.UNKNOWN,
            labelAr = "بيئة Android الحالية",
            detailAr = "لم يتم تحديد نوع الملف الشخصي بعد",
            isManagedProfile = false,
            isProfile = false,
            isSystemUser = false
        )
    }
}

object RuntimeProfileDetector {
    fun detect(context: Context): RuntimeProfileInfo {
        val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
        val handle = Process.myUserHandle()
        val handleText = handle.toString()
        val serial = runCatching { um?.getSerialNumberForUser(handle) ?: -1L }.getOrDefault(-1L)
        val managed = if (Build.VERSION.SDK_INT >= 30) runCatching { um?.isManagedProfile == true }.getOrDefault(false) else false
        val profile = if (Build.VERSION.SDK_INT >= 33) runCatching { um?.isProfile == true }.getOrDefault(managed) else managed
        val systemUser = runCatching { um?.isSystemUser == true }.getOrDefault(handleText == "UserHandle{0}")
        val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        val secondary = managed || !systemUser
        val kind = when {
            managed -> RuntimeProfileKind.WORK
            samsung && secondary -> RuntimeProfileKind.SAMSUNG_ISOLATED
            secondary -> RuntimeProfileKind.SECONDARY
            systemUser -> RuntimeProfileKind.PERSONAL
            else -> RuntimeProfileKind.UNKNOWN
        }
        val stable = if (serial >= 0L) serial.toString() else handleText
        val key = "${kind.name}:$stable"
        val label = when (kind) {
            RuntimeProfileKind.PERSONAL -> "الملف الشخصي"
            RuntimeProfileKind.WORK -> "ملف العمل"
            RuntimeProfileKind.SAMSUNG_ISOLATED -> "مجلد سامسونج الآمن / ملف معزول"
            RuntimeProfileKind.SECONDARY -> "ملف Android ثانوي"
            RuntimeProfileKind.UNKNOWN -> "بيئة Android الحالية"
        }
        val detail = when (kind) {
            RuntimeProfileKind.PERSONAL -> "تشغيل محلي داخل المستخدم الرئيسي. يتم قفل الحزمة والملف طوال المهمة."
            RuntimeProfileKind.WORK -> "تشغيل داخل Work Profile فقط. يجب أن تكون نسخة AL-thmany وWhatsApp في ملف العمل نفسه."
            RuntimeProfileKind.SAMSUNG_ISOLATED -> "تشغيل داخل بيئة Samsung معزولة. لا يتم تجاوز Knox؛ يتم استخدام الموارد المرئية داخل الملف الحالي فقط."
            RuntimeProfileKind.SECONDARY -> "تشغيل داخل Android user ثانوي مع Target Lock محلي."
            RuntimeProfileKind.UNKNOWN -> "سيتم استخدام نسخ WhatsApp المرئية لهذا Context فقط."
        }
        return RuntimeProfileInfo(kind, label, detail, managed, profile, systemUser, handleText, key, serial, samsung)
    }
}
