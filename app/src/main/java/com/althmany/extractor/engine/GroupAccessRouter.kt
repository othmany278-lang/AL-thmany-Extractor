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
        prepareGroupList(service, timing, waitForUi)

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
        repeat(5) {
            if (adapter.isConversationListVisible(service.currentRoot())) return
            service.performBack()
            waitForUi(timing.searchOpenMs)
        }
    }

    private suspend fun prepareGroupList(
        service: WhatsAppAccessibilityService,
        timing: TimingPolicy,
        waitForUi: suspend (Long) -> Unit
    ) {
        val root = service.currentRoot() ?: return
        if (!adapter.isConversationListVisible(root) || adapter.isGroupsFilterActive(root)) return
        val clicked = adapter.activateGroupsFilter(root) ||
            service.tapBounds(adapter.groupsFilterBounds(root), timing.gestureDurationMs)
        if (clicked) waitForUi(timing.searchOpenMs)
    }

    private suspend fun openVisible(
        group: TargetGroup,
        service: WhatsAppAccessibilityService,
        expectedPackage: String,
        timing: TimingPolicy,
        waitForUi: suspend (Long) -> Unit
    ): Boolean {
        val root = service.currentRoot() ?: return false
        val row = adapter.findVisibleChatListRow(root, group.name)
        val clicked = when {
            row == null -> false
            row.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) -> true
            else -> {
                val rect = android.graphics.Rect().also(row::getBoundsInScreen)
                service.tapBounds(rect, timing.gestureDurationMs)
            }
        }
        if (!clicked) return false
        waitForUi(timing.groupOpenMs)
        if (verifyChatOpen(group, service, expectedPackage, timing, waitForUi)) return true
        // Never start scrolling while an unverified chat is open. Return to the list first.
        if (!adapter.isConversationListVisible(service.currentRoot())) {
            service.performBack()
            waitForUi(timing.searchOpenMs)
            prepareGroupList(service, timing, waitForUi)
        }
        return false
    }

    private suspend fun verifyChatOpen(
        group: TargetGroup,
        service: WhatsAppAccessibilityService,
        expectedPackage: String,
        timing: TimingPolicy,
        waitForUi: suspend (Long) -> Unit
    ): Boolean {
        repeat(12) {
            if (adapter.isGroupVisible(service.currentRoot(), group.name, expectedPackage)) return true
            waitForUi(maxOf(timing.eventQuietMs, 140L))
        }
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
        // The old 2.14 router searched only toward the end of the list. After one group was opened,
        // the next selected group could be above the current list position and was never found.
        // Search both directions using fresh roots, then leave Search as the final fallback.
        val budget = maxScrollPasses.coerceIn(12, 700)
        if (scanDirection(group, service, expectedPackage, timing, waitForUi, forward = true, passes = budget / 2)) return true
        recoverToConversationList(service, timing, waitForUi)
        prepareGroupList(service, timing, waitForUi)
        if (scanDirection(group, service, expectedPackage, timing, waitForUi, forward = false, passes = budget)) return true
        recoverToConversationList(service, timing, waitForUi)
        prepareGroupList(service, timing, waitForUi)
        return scanDirection(group, service, expectedPackage, timing, waitForUi, forward = true, passes = budget)
    }

    private suspend fun scanDirection(
        group: TargetGroup,
        service: WhatsAppAccessibilityService,
        expectedPackage: String,
        timing: TimingPolicy,
        waitForUi: suspend (Long) -> Unit,
        forward: Boolean,
        passes: Int
    ): Boolean {
        var stable = 0
        var lastSignature: Int? = null
        repeat(passes.coerceAtLeast(1)) {
            val root = service.currentRoot() ?: run {
                waitForUi(timing.eventQuietMs)
                return@repeat
            }
            if (openVisible(group, service, expectedPackage, timing, waitForUi)) return true
            val before = adapter.snapshot(root).signature
            val accepted = if (forward) {
                adapter.scrollChatListForward(root) || service.swipeChatListForward(timing.gestureDurationMs)
            } else {
                adapter.scrollChatListBackward(root) || service.swipeChatListBackward(timing.gestureDurationMs)
            }
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
        return verifyChatOpen(group, service, expectedPackage, timing, waitForUi)
    }
}
