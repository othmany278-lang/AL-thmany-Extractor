package com.althmany.extractor.engine

import com.althmany.extractor.accessibility.WhatsAppAccessibilityService
import com.althmany.extractor.data.GroupAccessMethod
import com.althmany.extractor.data.TargetGroup

/**
 * Unified group opener shared by extraction and publishing.
 *
 * Important: AccessibilityNodeInfo is never cached here. Every attempt requests a fresh root from
 * the live service. The database stores only stable group metadata and the access method that last
 * succeeded.
 */
class GroupAccessRouter(private val adapter: WhatsAppUiAdapter) {
    data class Result(
        val opened: Boolean,
        val method: GroupAccessMethod = GroupAccessMethod.UNKNOWN,
        val attempted: List<GroupAccessMethod> = emptyList(),
        val detail: String = ""
    )

    suspend fun open(
        group: TargetGroup,
        service: WhatsAppAccessibilityService,
        expectedPackage: String,
        timing: TimingPolicy,
        waitForUi: suspend (Long) -> Unit,
        ensureForeground: suspend () -> Boolean,
        maxScrollPasses: Int = 260,
        allowSearchFallback: Boolean = true
    ): Result {
        val attempted = mutableListOf<GroupAccessMethod>()

        if (!ensureForeground()) {
            return Result(false, attempted = attempted, detail = "تعذر إحضار واتساب المحدد إلى الواجهة")
        }

        if (adapter.isGroupVisible(service.currentRoot(), group.name, expectedPackage)) {
            return Result(true, GroupAccessMethod.CURRENT_CHAT, attempted, "المحادثة المطلوبة مفتوحة بالفعل")
        }

        // Return to the conversation list without opening Search. A stale info/media screen is a
        // common reason older builds became stuck between groups.
        recoverToConversationList(service, timing, waitForUi)

        val priority = linkedSetOf<GroupAccessMethod>()
        // Cached access is useful only if it does not violate the global rule that Search is last.
        // Search may have succeeded in an older release, but 2.14 never promotes it ahead of list matching.
        val cachedNonSearch = listOf(group.preferredAccessMethod, group.lastSuccessfulOpenMethod)
            .filter { it != GroupAccessMethod.UNKNOWN && it != GroupAccessMethod.SEARCH_FALLBACK }
        cachedNonSearch.forEach { priority += it }
        priority += GroupAccessMethod.VISIBLE_LIST
        priority += GroupAccessMethod.SCROLL_MATCH
        if (allowSearchFallback) priority += GroupAccessMethod.SEARCH_FALLBACK

        for (method in priority) {
            attempted += method
            val opened = when (method) {
                GroupAccessMethod.CURRENT_CHAT ->
                    adapter.isGroupVisible(service.currentRoot(), group.name, expectedPackage)

                // Android WhatsApp does not expose an official stable JID-to-chat intent. Keep the
                // metadata field for when a supported route becomes available, but never fake it.
                GroupAccessMethod.JID_DIRECT,
                GroupAccessMethod.DIRECT_INTENT,
                GroupAccessMethod.SHARE_PICKER,
                GroupAccessMethod.RECENT_CHAT,
                GroupAccessMethod.UNKNOWN -> false

                GroupAccessMethod.VISIBLE_LIST ->
                    openVisible(group, service, expectedPackage, timing, waitForUi)

                GroupAccessMethod.SCROLL_MATCH ->
                    scrollAndMatch(group, service, expectedPackage, timing, waitForUi, maxScrollPasses)

                GroupAccessMethod.SEARCH_FALLBACK ->
                    if (allowSearchFallback) searchFallback(group, service, expectedPackage, timing, waitForUi) else false
            }
            if (opened) {
                return Result(true, method, attempted, "فتح القروب بواسطة ${method.labelAr}")
            }
        }

        return Result(false, attempted = attempted, detail = "فشلت طرق الوصول المتاحة بدون اختيار يدوي")
    }

    private suspend fun recoverToConversationList(
        service: WhatsAppAccessibilityService,
        timing: TimingPolicy,
        waitForUi: suspend (Long) -> Unit
    ) {
        repeat(4) {
            if (adapter.collectChatListCandidates(service.currentRoot()).size >= 2) return
            service.performBack()
            waitForUi(timing.searchOpenMs)
        }
    }

    private suspend fun openVisible(
        group: TargetGroup,
        service: WhatsAppAccessibilityService,
        expectedPackage: String,
        timing: TimingPolicy,
        waitForUi: suspend (Long) -> Unit
    ): Boolean {
        val root = service.currentRoot() ?: return false
        if (!adapter.openVisibleChatListRow(root, group.name)) return false
        waitForUi(timing.groupOpenMs)
        if (adapter.isGroupVisible(service.currentRoot(), group.name, expectedPackage)) return true
        waitForUi(timing.eventQuietMs)
        return adapter.isGroupVisible(service.currentRoot(), group.name, expectedPackage)
    }

    private suspend fun scrollAndMatch(
        group: TargetGroup,
        service: WhatsAppAccessibilityService,
        expectedPackage: String,
        timing: TimingPolicy,
        waitForUi: suspend (Long) -> Unit,
        maxScrollPasses: Int
    ): Boolean {
        var stable = 0
        var lastSignature: Int? = null
        repeat(maxScrollPasses.coerceIn(8, 700)) {
            val root = service.currentRoot() ?: run {
                waitForUi(timing.eventQuietMs)
                return@repeat
            }
            if (adapter.openVisibleChatListRow(root, group.name)) {
                waitForUi(timing.groupOpenMs)
                if (adapter.isGroupVisible(service.currentRoot(), group.name, expectedPackage)) return true
                recoverToConversationList(service, timing, waitForUi)
            }

            val before = adapter.snapshot(root).signature
            val accepted = adapter.scrollChatListForward(root) || service.swipeChatListForward(timing.gestureDurationMs)
            waitForUi(timing.eventQuietMs)
            val after = adapter.snapshot(service.currentRoot()).signature
            stable = if (!accepted || before == after || after == lastSignature) stable + 1 else 0
            lastSignature = after
            if (stable >= 3) return false
        }
        return false
    }

    private suspend fun searchFallback(
        group: TargetGroup,
        service: WhatsAppAccessibilityService,
        expectedPackage: String,
        timing: TimingPolicy,
        waitForUi: suspend (Long) -> Unit
    ): Boolean {
        var root = service.currentRoot()
        if (!adapter.findAndClickSearch(root)) return false
        waitForUi(timing.searchOpenMs)
        root = service.currentRoot()
        if (!adapter.setSearchText(root, group.name)) return false
        waitForUi(timing.searchResultMs)
        root = service.currentRoot()
        if (!adapter.openSearchResult(root, group.name)) return false
        waitForUi(timing.groupOpenMs)
        if (adapter.isGroupVisible(service.currentRoot(), group.name, expectedPackage)) return true
        waitForUi(timing.eventQuietMs)
        return adapter.isGroupVisible(service.currentRoot(), group.name, expectedPackage)
    }
}
