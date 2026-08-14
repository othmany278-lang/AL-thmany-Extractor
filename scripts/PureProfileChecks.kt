import com.althmany.extractor.profile.DualMessengerMatcher
import com.althmany.extractor.profile.ProfileControlCapability
import com.althmany.extractor.profile.ProfileControlPolicy
import com.althmany.extractor.profile.ProfileLaunchPolicy

fun main() {
    check(DualMessengerMatcher.isWhatsApp("WhatsApp"))
    check(DualMessengerMatcher.isWhatsApp("واتساب"))
    check(DualMessengerMatcher.isExplicitDualMessenger("WhatsApp Dual Messenger"))
    check(DualMessengerMatcher.isExplicitDualMessenger("نسخة واتساب"))
    check(!DualMessengerMatcher.isExplicitDualMessenger("WhatsApp"))

    check(ProfileLaunchPolicy.resolveSelected("com.whatsapp", listOf("com.whatsapp")) == "com.whatsapp")
    check(ProfileLaunchPolicy.resolveSelected(null, listOf("com.whatsapp", "com.whatsapp.w4b")) == null)
    check(ProfileLaunchPolicy.isMismatch("com.whatsapp", "vendor.whatsapp.clone"))
    check(!ProfileLaunchPolicy.isMismatch("com.whatsapp", "com.android.settings"))

    check(ProfileControlPolicy.classify(true, true, 10L, 10L, true) == ProfileControlCapability.READY)
    check(ProfileControlPolicy.classify(true, false, null, null, false) == ProfileControlCapability.SERVICE_NOT_CONNECTED_LOCALLY)
    println("PureProfileChecks v2.15: PASS")
}
