package com.althmany.extractor.profile

/** Pure selection/guard rules, testable without Android. */
object ProfileLaunchPolicy {
    /**
     * Keep a saved choice only if it is still launchable in this profile.
     * Auto-select only when there is exactly one possible WhatsApp instance.
     * With two instances we require an explicit user choice to avoid opening the wrong account.
     */
    fun resolveSelected(saved: String?, launchablePackages: List<String>): String? = when {
        saved != null && saved in launchablePackages -> saved
        launchablePackages.size == 1 -> launchablePackages.first()
        else -> null
    }

    fun isMismatch(expected: String?, observed: String?): Boolean =
        expected != null && observed != null && expected != observed &&
            (observed == "com.whatsapp" || observed == "com.whatsapp.w4b")
}
