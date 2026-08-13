package com.althmany.extractor.profile

import android.content.Context
import android.os.Build
import android.os.UserManager

/**
 * Runtime description of the Android user/profile that owns this app process.
 *
 * The extractor intentionally operates only inside the profile where this APK instance is installed.
 * That keeps Personal, Work Profile and Samsung Secure Folder data/processes isolated from each other.
 */
enum class RuntimeProfileKind {
    PERSONAL,
    WORK,
    SAMSUNG_ISOLATED,
    ISOLATED,
    UNKNOWN
}

data class RuntimeProfileInfo(
    val kind: RuntimeProfileKind,
    val labelAr: String,
    val detailAr: String,
    val isManagedProfile: Boolean,
    val isProfile: Boolean,
    val isSystemUser: Boolean
) {
    val isIsolated: Boolean get() = kind != RuntimeProfileKind.PERSONAL

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
        val userManager = context.getSystemService(UserManager::class.java)
        val managed = if (Build.VERSION.SDK_INT >= 30) userManager?.isManagedProfile == true else false
        val profile = if (Build.VERSION.SDK_INT >= 33) userManager?.isProfile == true else managed
        val systemUser = runCatching { userManager?.isSystemUser == true }.getOrDefault(false)
        val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

        return when {
            managed -> RuntimeProfileInfo(
                RuntimeProfileKind.WORK,
                "ملف العمل",
                "هذه النسخة تعمل داخل Work Profile؛ ستتعامل فقط مع تطبيقات واتساب المرئية داخل ملف العمل.",
                managed, profile, systemUser
            )
            profile && samsung -> RuntimeProfileInfo(
                RuntimeProfileKind.SAMSUNG_ISOLATED,
                "ملف سامسونج معزول / المجلد الآمن",
                "هذه النسخة تعمل داخل ملف سامسونج معزول. يتم استخدام واتساب الموجود في نفس الملف فقط.",
                managed, profile, systemUser
            )
            profile -> RuntimeProfileInfo(
                RuntimeProfileKind.ISOLATED,
                "ملف Android معزول",
                "هذه النسخة تعمل داخل Profile مستقل؛ لن تفتح تطبيقات الملف الشخصي الآخر.",
                managed, profile, systemUser
            )
            !systemUser && samsung -> RuntimeProfileInfo(
                RuntimeProfileKind.SAMSUNG_ISOLATED,
                "بيئة سامسونج معزولة محتملة",
                "تعذر تصنيف الحاوية بدقة على إصدار Android هذا؛ سيظل التطبيق مقيدًا بتطبيقات البيئة الحالية فقط.",
                managed, profile, systemUser
            )
            systemUser -> RuntimeProfileInfo(
                RuntimeProfileKind.PERSONAL,
                "الملف الشخصي",
                "هذه النسخة تعمل في الملف الشخصي الرئيسي.",
                managed, profile, systemUser
            )
            else -> RuntimeProfileInfo(
                RuntimeProfileKind.UNKNOWN,
                "بيئة Android الحالية",
                "سيستخدم التطبيق فقط نسخ واتساب التي يستطيع PackageManager رؤيتها في هذه البيئة.",
                managed, profile, systemUser
            )
        }
    }
}
