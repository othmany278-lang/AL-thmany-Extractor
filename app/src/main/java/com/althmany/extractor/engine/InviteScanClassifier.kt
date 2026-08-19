package com.althmany.extractor.engine

import com.althmany.extractor.data.InviteKind
import com.althmany.extractor.data.ScanStatus
import java.util.Locale

data class InviteScanDecision(
    val status: ScanStatus,
    val detail: String,
    val definitive: Boolean,
    val confidence: Int = 0,
    val signalCode: String = "UNKNOWN",
    val groupName: String? = null,
    val memberCountText: String? = null,
    val inviteKind: InviteKind = InviteKind.UNKNOWN
)

/**
 * Read-only invite preview analyser. It classifies only what WhatsApp exposes through Accessibility.
 * No rule in this class clicks Join, Request or any other membership action.
 */
object InviteScanClassifier {
    private data class Rule(
        val status: ScanStatus,
        val code: String,
        val phrases: List<String>,
        val detail: String,
        val confidence: Int = 98
    )

    private val rules = listOf(
        Rule(ScanStatus.INVALID, "INVITE_INVALID", listOf(
            "رابط الدعوة غير صالح", "رابط الدعوة غير صالح أو تم إلغاؤه", "تمت إعادة تعيين رابط الدعوة",
            "تعذر الحصول على معلومات المجموعة", "هذه الدعوة غير صالحة", "invite link is invalid",
            "invite link was reset", "couldn't get group info", "could not get group info", "this invite link is invalid",
            "invite link has been revoked", "invite link expired"
        ), "رابط الدعوة غير صالح/منتهي أو تمت إعادة تعيينه", 100),
        Rule(ScanStatus.FULL, "GROUP_FULL", listOf(
            "المجموعة ممتلئة", "المجموعة مكتملة", "group is full", "this group is full"
        ), "المجموعة ممتلئة", 100),
        Rule(ScanStatus.REMOVED, "REMOVED_FROM_GROUP", listOf(
            "تمت إزالتك", "تم إزالتك", "لا يمكنك الانضمام إلى هذه المجموعة لأنه تم إزالتك",
            "you were removed", "you can't join this group because you were removed", "you cannot join this group because you were removed"
        ), "الحساب تمت إزالته من المجموعة", 100),
        Rule(ScanStatus.ACCOUNT_LIMIT, "ACCOUNT_LIMIT", listOf(
            "لا يمكنك الانضمام إلى المزيد من المجموعات", "وصلت إلى الحد الأقصى للمجموعات", "تعذر الانضمام إلى المزيد من المجموعات",
            "you can't join more groups", "you cannot join more groups", "maximum number of groups", "too many groups"
        ), "الحساب وصل إلى حد يمنع الانضمام حاليًا", 99),
        Rule(ScanStatus.REQUEST_PENDING, "REQUEST_PENDING", listOf(
            "إلغاء الطلب", "تم إرسال طلب الانضمام", "طلب الانضمام قيد المراجعة", "طلبك قيد المراجعة",
            "cancel request", "request sent", "request pending", "your request is pending"
        ), "يوجد طلب انضمام مرسل بالفعل", 100),
        Rule(ScanStatus.APPROVAL, "APPROVAL_REQUIRED", listOf(
            "طلب الانضمام", "إرسال طلب الانضمام", "ارسال طلب الانضمام", "طلب للانضمام",
            "طلب الانضمام إلى القروب", "طلب الانضمام الى القروب", "طلب الانضمام للقروب",
            "اطلب الانضمام إلى المجموعة", "اطلب الانضمام الى المجموعة", "اطلب الانضمام للمجموعة",
            "اطلب الانضمام إلى المجتمع", "اطلب الانضمام الى المجتمع", "اطلب الانضمام للمجتمع",
            "طلب دخول", "request to join", "send request", "request to join group",
            "request membership", "request group access", "ask for access", "send request to join",
            "request to join community", "ask to join community", "request community access"
        ), "الانضمام يحتاج موافقة المشرف", 99),
        Rule(ScanStatus.DIRECT, "DIRECT_JOIN", listOf(
            "الانضمام إلى المجموعة", "الانضمام الى المجموعة", "الانضمام للمجموعة",
            "انضمام إلى المجموعة", "انضمام الى المجموعة", "انضم إلى المجموعة", "انضم الى المجموعة", "انضم للمجموعة",
            "الانضمام إلى القروب", "الانضمام الى القروب", "انضم إلى القروب", "انضم الى القروب", "انضم للقروب",
            "الانضمام إلى المجتمع", "الانضمام الى المجتمع", "الانضمام للمجتمع",
            "انضم إلى المجتمع", "انضم الى المجتمع", "انضم للمجتمع",
            "join group", "join the group", "join this group", "join group now",
            "join this chat", "join this group now",
            "join community", "join this community", "join the community",
            "join community now", "join this community now", "join community chat"
        ), "الرابط يتيح الانضمام المباشر", 99),
        Rule(ScanStatus.ALREADY_MEMBER, "ALREADY_MEMBER", listOf(
            "أنت عضو بالفعل", "أنت بالفعل عضو", "فتح المجموعة", "عرض المجموعة",
            "you're already a member", "you are already a member", "open group", "view group"
        ), "الحساب عضو بالفعل أو واتساب فتح المجموعة مباشرة", 96),
        Rule(ScanStatus.NETWORK_ERROR, "NETWORK_ERROR", listOf(
            "تحقق من اتصالك بالإنترنت", "تعذر الاتصال", "أعد المحاولة لاحقًا", "check your internet connection",
            "couldn't connect", "could not connect", "try again later", "network error"
        ), "تعذر تأكيد حالة الدعوة بسبب الاتصال", 96)
    )

    private val genericUi = setOf(
        "واتساب", "whatsapp", "رجوع", "back", "مشاركة", "share", "موافق", "ok", "إلغاء", "cancel",
        "المجموعة", "group", "المجتمع", "community", "الدردشة", "chat", "معلومات", "info",
        "الدردشات", "chats", "المجتمعات", "communities", "التحديثات", "updates",
        "المكالمات", "calls", "الحالة", "status"
    )

    fun classify(texts: Collection<String>): InviteScanDecision {
        val cleanTexts = texts.asSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        val normalized = cleanTexts.joinToString("\n") { it.lowercase(Locale.ROOT) }
        val metadata = detectMetadata(cleanTexts, normalized)

        for (rule in rules) {
            val matched = rule.phrases.firstOrNull { normalized.contains(it.lowercase(Locale.ROOT)) }
            if (matched != null) {
                return InviteScanDecision(
                    status = rule.status,
                    detail = rule.detail,
                    definitive = true,
                    confidence = rule.confidence,
                    signalCode = rule.code,
                    groupName = metadata.groupName,
                    memberCountText = metadata.memberCountText,
                    inviteKind = metadata.inviteKind
                )
            }
        }

        // A real invite preview often exposes group metadata before the action button becomes visible.
        // Keep it UNKNOWN, but surface a confidence hint for the controller instead of guessing a status.
        val confidence = when {
            metadata.groupName != null && metadata.memberCountText != null -> 55
            metadata.groupName != null -> 42
            cleanTexts.size >= 8 -> 25
            else -> 10
        }
        return InviteScanDecision(
            ScanStatus.UNKNOWN,
            "لم تظهر حالة مؤكدة بعد",
            definitive = false,
            confidence = confidence,
            signalCode = if (metadata.groupName != null) "PREVIEW_VISIBLE" else "NO_DEFINITIVE_SIGNAL",
            groupName = metadata.groupName,
            memberCountText = metadata.memberCountText,
            inviteKind = metadata.inviteKind
        )
    }

    private data class Metadata(val groupName: String?, val memberCountText: String?, val inviteKind: InviteKind)

    private fun detectMetadata(texts: List<String>, normalized: String): Metadata {
        // Do NOT classify an invite as a community just because WhatsApp's bottom navigation
        // contains "Communities/المجتمعات". Invite kind must come from invite-specific semantics.
        val communitySignals = listOf(
            "الانضمام إلى المجتمع", "الانضمام الى المجتمع", "الانضمام للمجتمع",
            "انضم إلى المجتمع", "انضم الى المجتمع", "انضم للمجتمع",
            "اطلب الانضمام إلى المجتمع", "اطلب الانضمام الى المجتمع", "اطلب الانضمام للمجتمع",
            "طلب الانضمام إلى المجتمع", "طلب الانضمام الى المجتمع", "طلب الانضمام للمجتمع",
            "استكشاف المجتمع", "join community", "join this community", "join the community",
            "join community now", "join this community now", "join community chat",
            "request to join community", "ask to join community", "request community access",
            "explore community"
        )
        val groupSignals = listOf(
            "الانضمام إلى المجموعة", "الانضمام الى المجموعة", "الانضمام للمجموعة",
            "انضمام إلى المجموعة", "انضمام الى المجموعة", "انضم إلى المجموعة", "انضم الى المجموعة", "انضم للمجموعة",
            "الانضمام إلى القروب", "الانضمام الى القروب", "انضم إلى القروب", "انضم الى القروب", "انضم للقروب",
            "طلب الانضمام إلى المجموعة", "طلب الانضمام الى المجموعة", "طلب الانضمام للمجموعة",
            "طلب الانضمام إلى القروب", "طلب الانضمام الى القروب", "طلب الانضمام للقروب",
            "اطلب الانضمام إلى المجموعة", "اطلب الانضمام الى المجموعة", "اطلب الانضمام للمجموعة",
            "join group", "join the group", "join this group", "join group now",
            "join this chat", "join this group now", "request to join group", "request group access"
        )
        val kind = when {
            communitySignals.any { normalized.contains(it.lowercase(Locale.ROOT)) } -> InviteKind.COMMUNITY
            groupSignals.any { normalized.contains(it.lowercase(Locale.ROOT)) } -> InviteKind.GROUP
            else -> InviteKind.UNKNOWN
        }

        val countRegexes = listOf(
            Regex("(?i)\\b[0-9٠-٩۰-۹][0-9٠-٩۰-۹,.٬، ]{0,10}\\s*(?:مشارك(?:ًا|ا)?|مشاركون|عضو|أعضاء)\\b"),
            Regex("(?i)\\b[0-9][0-9,. ]{0,10}\\s*(?:participants?|members?)\\b")
        )
        val memberCount = texts.firstNotNullOfOrNull { text ->
            countRegexes.firstNotNullOfOrNull { rx -> rx.find(text)?.value }
        }

        val forbiddenPhrases = rules.flatMap { it.phrases }.map { it.lowercase(Locale.ROOT) }
        val groupName = texts.firstOrNull { value ->
            val s = value.trim()
            val lower = s.lowercase(Locale.ROOT)
            s.length in 2..90 &&
                s.any(Char::isLetter) &&
                !s.contains("http://", true) && !s.contains("https://", true) &&
                !countRegexes.any { it.containsMatchIn(s) } &&
                lower !in genericUi &&
                forbiddenPhrases.none { lower.contains(it) } &&
                !lower.contains("chat.whatsapp.com") &&
                !s.matches(Regex("^[0-9٠-٩۰-۹:./+\\- ]+$"))
        }
        return Metadata(groupName, memberCount, kind)
    }
}
