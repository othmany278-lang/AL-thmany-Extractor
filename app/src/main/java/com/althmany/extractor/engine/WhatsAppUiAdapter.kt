package com.althmany.extractor.engine

import android.graphics.Rect
import android.text.Spanned
import android.text.style.URLSpan
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.althmany.extractor.data.GroupSyncCandidate
import java.util.Locale
import java.text.Normalizer

/**
 * WhatsApp-specific heuristics are isolated here. No database or job state is kept in this class.
 * That makes WhatsApp UI changes patchable without rewriting the extraction coordinator.
 */
class WhatsAppUiAdapter {
    private val searchLabels = listOf("بحث", "Search")
    private val groupsFilterLabels = listOf("المجموعات", "مجموعات", "Groups")
    private val chatsTabLabels = listOf("الدردشات", "دردشات", "Chats")
    private val chatFilterLabels = listOf(
        "الكل", "All", "غير مقروءة", "غير المقروءة", "Unread",
        "المفضلة", "Favorites", "المجموعات", "Groups"
    )
    private val mediaEntryPatterns = listOf(
        "الوسائط والروابط والمستندات", "الوسائط، الروابط والمستندات", "الوسائط والروابط والوثائق",
        "Media, links, and docs", "Media, links and docs", "Media, links & docs"
    )
    private val linksTabPatterns = listOf("الروابط", "روابط", "Links", "Link")
    private val olderLoaderPatterns = listOf(
        "مشاهدة الرسائل الأقدم", "تحميل الرسائل الأقدم", "اضغط هنا لمشاهدة الرسائل الأقدم",
        "Tap here to load older messages", "Load older messages", "View older messages"
    )
    private val unreadPatterns = listOf("رسائل غير مقروءة", "رسالة غير مقروءة", "Unread messages", "Unread message")
    private val archivedPatterns = listOf("مؤرشفة", "المؤرشفة", "Archived")
    private val inviteJoinLabels = listOf(
        "الانضمام إلى المجموعة", "الانضمام الى المجموعة", "الانضمام للمجموعة",
        "انضم إلى المجموعة", "انضم الى المجموعة", "انضم للمجموعة",
        "الانضمام إلى القروب", "الانضمام الى القروب", "انضم إلى القروب", "انضم الى القروب", "انضم للقروب",
        "الانضمام إلى المجتمع", "الانضمام الى المجتمع", "الانضمام للمجتمع",
        "انضم إلى المجتمع", "انضم الى المجتمع", "انضم للمجتمع",
        "Join group", "Join the group", "Join this group", "Join group now",
        "Join this chat", "Join this group now",
        "Join community", "Join this community", "Join the community",
        "Join community now", "Join this community now", "Join community chat"
    )
    private val inviteRequestLabels = listOf(
        "طلب الانضمام", "طلب الانضمام إلى المجموعة", "طلب الانضمام الى المجموعة", "طلب الانضمام للمجموعة",
        "إرسال طلب الانضمام", "ارسال طلب الانضمام", "طلب للانضمام",
        "طلب الانضمام إلى القروب", "طلب الانضمام الى القروب", "طلب الانضمام للقروب",
        "اطلب الانضمام إلى المجموعة", "اطلب الانضمام الى المجموعة", "اطلب الانضمام للمجموعة",
        "اطلب الانضمام إلى المجتمع", "اطلب الانضمام الى المجتمع", "اطلب الانضمام للمجتمع",
        "Request to join", "Send request", "Request to join group",
        "Request membership", "Request group access", "Ask for access", "Send request to join",
        "Request to join community", "Ask to join community", "Request community access"
    )
    private val inviteConfirmationLabels = listOf(
        "متابعة", "تابع", "تأكيد", "تاكيد", "تأكيد الانضمام", "تاكيد الانضمام",
        "موافق", "نعم", "استمرار",
        "Continue", "Confirm", "Confirm join", "Continue to join", "Continue joining",
        "Proceed", "Yes", "OK", "Okay"
    )
    private val inviteCloseLabels = listOf("إغلاق", "اغلاق", "Close", "Dismiss")
    private val inviteJoinIds = listOf(
        "join_group", "group_join", "group_join_button", "join_group_button",
        "join_community", "community_join", "community_join_button", "join_community_button"
    )
    private val inviteRequestIds = listOf(
        "request_join", "request_to_join", "join_request", "send_join_request",
        "request_community", "community_request",
        "request_join_button", "request_to_join_button", "join_request_button",
        "send_join_request_button", "request_community_button", "community_request_button"
    )
    private val inviteConfirmationIds = listOf(
        "confirm_button", "confirmation_button", "continue_button",
        "join_confirm_button", "positive_button"
    )
    private val inviteCloseIds = listOf("close", "close_button", "dismiss_button", "cancel_button")

    private val terminalPatterns = listOf(
        Triple("phone-history-limit", "استخدم هاتفك الآخر لتحميل بقية الرسائل", true),
        Triple("phone-history-limit", "Use your phone to view older messages", true),
        Triple("conversation-start-card", "لقد انضممت لهذا القروب", true),
        Triple("conversation-start-card", "لقد انضممت عبر رابط الدعوة", true),
        Triple("conversation-start-card", "لقد انضممت من المجتمع", true),
        Triple("conversation-start-card", "You joined via an invite link", true),
        Triple("conversation-start-card", "You joined this group", true),
        Triple("community-explore", "استكشاف المجتمع", true),
        Triple("community-explore", "Explore community", true)
    )

    private val strongGroupInfoPatterns = listOf(
        "مغادرة المجموعة", "إضافة أعضاء", "إضافة مشاركين", "دعوة عبر رابط", "أذونات المجموعة", "إعدادات المجموعة",
        "Exit group", "Add members", "Add participants", "Invite via link", "Group permissions", "Group settings"
    )

    fun isWhatsAppRoot(root: AccessibilityNodeInfo?, expectedPackage: String? = null): Boolean {
        val pkg = root?.packageName?.toString() ?: return false
        return if (expectedPackage != null) {
            pkg == expectedPackage
        } else {
            pkg == ExtractionController.WHATSAPP || pkg == ExtractionController.WHATSAPP_BUSINESS
        }
    }

    fun snapshot(root: AccessibilityNodeInfo?): NodeSnapshot {
        if (root == null) return NodeSnapshot(emptyList(), 0, 0, emptyList(), false, 0, 0)
        val texts = ArrayList<String>(180)
        val messageTokens = ArrayList<String>(120)
        var scrollable = false
        var visibleCount = 0

        walk(root) { node ->
            if (!node.isVisibleToUser) return@walk
            visibleCount++
            if (node.isScrollable) scrollable = true
            node.text?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let {
                texts.add(it)
                if (looksLikeContentToken(it)) messageTokens.add(normalizeToken(it))
            }
            node.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let {
                texts.add(it)
                if (looksLikeContentToken(it)) messageTokens.add(normalizeToken(it))
            }
        }

        val normalizedAll = texts.map(String::trim).filter(String::isNotEmpty).distinct()
        val normalizedMessages = messageTokens.filter(String::isNotEmpty).distinct()
        val anchors = buildAnchorTokens(normalizedMessages)
        return NodeSnapshot(
            texts = normalizedAll,
            signature = normalizedAll.joinToString("\u0001").hashCode(),
            contentSignature = normalizedMessages.joinToString("\u0002").hashCode(),
            anchorTokens = anchors,
            scrollableNodeFound = scrollable,
            visibleNodeCount = visibleCount,
            messageTokenCount = normalizedMessages.size
        )
    }

    fun findAndClickSearch(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val byText = searchLabels.asSequence()
            .flatMap { root.findAccessibilityNodeInfosByText(it).asSequence() }
            .firstOrNull { it.isVisibleToUser }
        if (byText != null && clickNodeOrParent(byText)) return true

        var candidate: AccessibilityNodeInfo? = null
        walk(root) { node ->
            val desc = node.contentDescription?.toString().orEmpty()
            if (candidate == null && node.isVisibleToUser && searchLabels.any { desc.contains(it, ignoreCase = true) }) candidate = node
        }
        return candidate?.let(::clickNodeOrParent) == true
    }

    fun setSearchText(root: AccessibilityNodeInfo?, query: String): Boolean {
        if (root == null) return false
        var editable: AccessibilityNodeInfo? = null
        walk(root) { node -> if (editable == null && node.isEditable && node.isVisibleToUser) editable = node }
        val target = editable ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query) }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun openSearchResult(root: AccessibilityNodeInfo?, exactName: String): Boolean {
        if (root == null) return false
        val wanted = exactName.trim()
        var best: AccessibilityNodeInfo? = null
        var bestTop = Int.MAX_VALUE
        walk(root) { node ->
            if (!node.isVisibleToUser) return@walk
            val labels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            if (labels.none { groupNamesEquivalent(it, wanted) }) return@walk
            val rect = Rect().also(node::getBoundsInScreen)
            if (rect.top < bestTop && hasClickableSelfOrAncestor(node)) { bestTop = rect.top; best = node }
        }
        return best?.let(::clickNodeOrParent) == true
    }

    fun isGroupVisible(root: AccessibilityNodeInfo?, groupName: String, expectedPackage: String? = null): Boolean {
        if (root == null || !isWhatsAppRoot(root, expectedPackage)) return false
        // A list-row title can sit near the top of WhatsApp too. Reject the main conversation list
        // before accepting the header. Group-name comparison is normalized to survive bidi marks,
        // Arabic diacritics, variation selectors and whitespace differences exposed by WhatsApp.
        if (isConversationListVisible(root) || isGroupInfoScreen(root)) return false
        return currentChatHeaderNode(root, groupName) != null
    }

    /**
     * Structural confirmation used only after the router has clicked a row/search result that already
     * matched the requested group. Some WhatsApp/Samsung builds do not expose the chat title in the
     * accessibility header even though the conversation is genuinely open.
     */
    fun isConversationOpenForTarget(
        root: AccessibilityNodeInfo?,
        groupName: String,
        expectedPackage: String? = null
    ): Boolean {
        if (root == null || !isWhatsAppRoot(root, expectedPackage)) return false
        if (isConversationListVisible(root) || isGroupInfoScreen(root)) return false
        if (currentChatHeaderNode(root, groupName) != null) return true
        // Composer is the strongest generic signal of an opened writable chat. For read-only groups,
        // accept a message-like scrollable content region plus a visible top header candidate.
        if (findMessageComposer(root) != null) return true
        val window = Rect().also(root::getBoundsInScreen)
        var hasLargeScrollable = false
        var hasHeaderCandidate = false
        walk(root) { node ->
            if (!node.isVisibleToUser) return@walk
            val rect = Rect().also(node::getBoundsInScreen)
            if (node.isScrollable && rect.height() > window.height() * 0.28f && rect.width() > window.width() * 0.55f) {
                hasLargeScrollable = true
            }
            if (rect.top <= window.top + (window.height() * 0.28f).toInt()) {
                val label = node.text?.toString().orEmpty().ifBlank { node.contentDescription?.toString().orEmpty() }
                if (label.isNotBlank() && looksLikeConversationTitle(label)) hasHeaderCandidate = true
            }
        }
        return hasLargeScrollable && hasHeaderCandidate
    }

    /** True only for the main Chats list, never for an opened conversation/info/search screen. */
    fun isConversationListVisible(root: AccessibilityNodeInfo?): Boolean {
        if (root == null || !isWhatsAppRoot(root)) return false
        if (isGroupInfoScreen(root)) return false
        if (findMessageComposer(root) != null) return false
        val window = Rect().also(root::getBoundsInScreen)
        val bottomStart = window.top + (window.height() * 0.70f).toInt()
        val bottomNavLabels = listOf("الدردشات", "دردشات", "Chats", "التحديثات", "Updates", "المجتمعات", "Communities", "المكالمات", "Calls")
        var hasBottomNav = false
        var hasChatsTab = false
        walk(root) { node ->
            if (!node.isVisibleToUser) return@walk
            val r = Rect().also(node::getBoundsInScreen)
            if (r.top < bottomStart) return@walk
            val label = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ").trim()
            if (bottomNavLabels.any { label.equals(it, true) || label.contains(it, true) }) hasBottomNav = true
            if (chatsTabLabels.any { label.equals(it, true) || label.contains(it, true) }) hasChatsTab = true
        }
        val hasFilter = findVisibleFilterChip(root, chatFilterLabels) != null
        val hasArchived = archivedPatterns.any { pattern ->
            root.findAccessibilityNodeInfosByText(pattern).any { it.isVisibleToUser }
        }
        val list = findChatListContainer(root)
        val rowHints = collectChatListCandidatesDetailedInternal(root, list ?: root, limit = 4).isNotEmpty()
        // Latest WhatsApp builds sometimes omit isScrollable from the RecyclerView. Bottom navigation
        // plus row hints is enough to recognize the Chats surface without backing out of WhatsApp.
        return hasFilter || hasArchived || (hasBottomNav && (hasChatsTab || rowHints)) || (list != null && rowHints)
    }

    /** Best-effort activation of WhatsApp's real Groups filter shown above the chat list. */
    fun activateGroupsFilter(root: AccessibilityNodeInfo?): Boolean {
        val node = findVisibleFilterChip(root, groupsFilterLabels) ?: return false
        if (selectedOrCheckedInChain(node)) return true
        return clickNodeOrParent(node)
    }

    /** Bring WhatsApp back to the Chats tab before list/group operations. */
    fun activateChatsTab(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val window = Rect().also(root::getBoundsInScreen)
        val minTop = window.top + (window.height() * 0.66f).toInt()
        var candidate: AccessibilityNodeInfo? = null
        walk(root) { node ->
            if (candidate != null || !node.isVisibleToUser) return@walk
            val rect = Rect().also(node::getBoundsInScreen)
            if (rect.top < minTop) return@walk
            val label = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ").trim()
            if (chatsTabLabels.any { label.equals(it, true) || label.contains(it, true) } && hasClickableSelfOrAncestor(node)) candidate = node
        }
        return candidate?.let(::clickNodeOrParent) == true
    }

    fun groupsFilterBounds(root: AccessibilityNodeInfo?): Rect? =
        findVisibleFilterChip(root, groupsFilterLabels)?.let { Rect().also(it::getBoundsInScreen) }

    fun isGroupsFilterActive(root: AccessibilityNodeInfo?): Boolean {
        val node = findVisibleFilterChip(root, groupsFilterLabels) ?: return false
        if (selectedOrCheckedInChain(node)) return true
        var current: AccessibilityNodeInfo? = node
        val descriptions = mutableListOf<String>()
        repeat(5) {
            val n = current ?: return@repeat
            n.contentDescription?.toString()?.let(descriptions::add)
            current = n.parent
        }
        val desc = descriptions.joinToString(" ")
        return listOf("محدد", "مختار", "selected", "checked").any { desc.contains(it, true) }
    }

    fun collectVisibleUrls(root: AccessibilityNodeInfo?): Set<String> {
        if (root == null) return emptySet()
        val urls = linkedSetOf<String>()
        walk(root) { node ->
            if (!node.isVisibleToUser) return@walk

            // Android has no WhatsApp-Web DOM/Store. Merge every URL-bearing surface that the
            // real app exposes through the accessibility hierarchy: message body/caption text,
            // preview descriptions, hints/tooltips and URLSpan metadata.
            val text = node.text
            LinkExtractor.extract(text).forEach(urls::add)
            LinkExtractor.extract(node.contentDescription).forEach(urls::add)
            LinkExtractor.extract(node.hintText).forEach(urls::add)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                LinkExtractor.extract(node.tooltipText).forEach(urls::add)
            }
            if (text is Spanned) {
                text.getSpans(0, text.length, URLSpan::class.java)
                    .mapNotNull { it.url }
                    .forEach { LinkExtractor.extract(it).forEach(urls::add) }
            }

            // Some WhatsApp builds attach preview/link strings as node extras. Read only public
            // accessibility extras and ignore opaque values; no private database/Store access.
            runCatching {
                node.extras.keySet().forEach { key ->
                    val value = node.extras.get(key)
                    if (value is CharSequence) LinkExtractor.extract(value).forEach(urls::add)
                }
            }
        }
        return urls
    }

    fun scrollToOlderMessages(root: AccessibilityNodeInfo?): Boolean {
        val candidate = findBestScrollable(root) ?: return false
        return candidate.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    fun scrollGenericForward(root: AccessibilityNodeInfo?): Boolean {
        val candidate = findBestScrollable(root) ?: return false
        return candidate.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    fun clickOlderMessagesLoader(root: AccessibilityNodeInfo?): Boolean {
        val node = findVisibleNodeByPatterns(root, olderLoaderPatterns) ?: return false
        // A member may literally write "load older messages". Only treat the phrase as the WhatsApp
        // loader if Accessibility exposes a real clickable action in its ancestor chain.
        if (!hasClickableSelfOrAncestor(node)) return false
        return clickNodeOrParent(node)
    }

    fun olderMessagesLoaderVisible(root: AccessibilityNodeInfo?): Boolean {
        val node = findVisibleNodeByPatterns(root, olderLoaderPatterns) ?: return false
        return hasClickableSelfOrAncestor(node)
    }

    fun unreadDividerVisible(root: AccessibilityNodeInfo?): Boolean {
        val node = findVisibleNodeByPatterns(root, unreadPatterns) ?: return false
        // Unread separators are status elements, not text inside an interactive message bubble.
        return !node.isClickable && !node.isLongClickable && !hasInteractiveAncestor(node)
    }

    fun detectTerminalBoundary(root: AccessibilityNodeInfo?): TerminalBoundary? {
        if (root == null) return null
        for ((code, phrase, _) in terminalPatterns) {
            val candidates = root.findAccessibilityNodeInfosByText(phrase)
            for (node in candidates) {
                if (!node.isVisibleToUser) continue
                val actual = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ")
                if (!actual.contains(phrase, ignoreCase = true)) continue
                // Structural gate: a terminal phrase must look like a WhatsApp system/status node,
                // not text living inside an ordinary message bubble. Message containers are commonly
                // clickable/long-clickable even when the TextView itself is not.
                val structural = !node.isEditable &&
                    !node.isClickable &&
                    !node.isLongClickable &&
                    !hasClickableDescendant(node) &&
                    !hasInteractiveAncestor(node)
                if (structural) return TerminalBoundary(code, phrase, true)
            }
        }
        return null
    }

    fun openCurrentChatInfo(root: AccessibilityNodeInfo?, groupName: String): Boolean {
        val header = currentChatHeaderNode(root, groupName) ?: return false
        return clickNodeOrParent(header)
    }

    fun isGroupInfoScreen(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        return strongGroupInfoPatterns.any { pattern ->
            root.findAccessibilityNodeInfosByText(pattern).any { it.isVisibleToUser }
        }
    }

    fun openMediaLinksDocs(root: AccessibilityNodeInfo?): Boolean {
        val node = findVisibleNodeByPatterns(root, mediaEntryPatterns) ?: return false
        return clickNodeOrParent(node)
    }

    fun openLinksTab(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        // Require a short, exact tab-like label to avoid clicking a message containing the word links.
        var candidate: AccessibilityNodeInfo? = null
        walk(root) { node ->
            if (candidate != null || !node.isVisibleToUser) return@walk
            val text = node.text?.toString()?.trim().orEmpty()
            val clean = text.replace(Regex("[()（）\\[\\]0-9٠-٩۰-۹,،:·|\\-–—\\s]+"), "").lowercase(Locale.ROOT)
            val exact = clean in setOf("روابط", "الروابط", "link", "links")
            if (exact && (node.isClickable || node.parent?.isClickable == true)) candidate = node
        }
        return candidate?.let(::clickNodeOrParent) == true
    }

    fun linksTabLooksOpen(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val hasTab = linksTabPatterns.any { root.findAccessibilityNodeInfosByText(it).any { node -> node.isVisibleToUser } }
        val hasUrl = collectVisibleUrls(root).isNotEmpty()
        return hasTab && (hasUrl || findBestScrollable(root) != null)
    }

    /**
     * Collects chat-list candidates plus the lightweight metadata WhatsApp exposes visually.
     * These are hints only; a candidate is not treated as a verified group until the engine opens
     * its info surface and sees strong group-only controls.
     */
    fun collectChatListCandidatesDetailed(root: AccessibilityNodeInfo?): Set<GroupSyncCandidate> {
        if (root == null) return emptySet()
        val listRoot = findChatListContainer(root) ?: root
        return collectChatListCandidatesDetailedInternal(root, listRoot, limit = Int.MAX_VALUE)
    }

    private fun collectChatListCandidatesDetailedInternal(
        root: AccessibilityNodeInfo,
        listRoot: AccessibilityNodeInfo,
        limit: Int
    ): Set<GroupSyncCandidate> {
        val listBounds = Rect().also(listRoot::getBoundsInScreen)
        val window = Rect().also(root::getBoundsInScreen)
        val minWidth = (window.width() * 0.46f).toInt().coerceAtLeast(1)
        val topGuard = window.top + (window.height() * 0.08f).toInt()
        val bottomGuard = window.top + (window.height() * 0.94f).toInt()
        val byName = linkedMapOf<String, GroupSyncCandidate>()
        val visitedRows = hashSetOf<String>()

        fun acceptRow(row: AccessibilityNodeInfo) {
            if (byName.size >= limit || !row.isVisibleToUser || !row.isEnabled) return
            val bounds = Rect().also(row::getBoundsInScreen)
            if (bounds.top < topGuard || bounds.bottom > bottomGuard || bounds.height() !in 38..430 || bounds.width() < minWidth) return
            if (listRoot !== root && !Rect.intersects(listBounds, bounds)) return
            val rowKey = "${bounds.left}:${bounds.top}:${bounds.right}:${bounds.bottom}"
            if (!visitedRows.add(rowKey)) return
            val title = conversationTitleFromRow(row) ?: return
            if (!looksLikeConversationTitle(title)) return
            val labels = descendantLabels(row, 36)
            val joined = labels.joinToString(" | ")
            val unread = parseUnreadCount(joined)
            val inactive = inactiveConversationPatterns.any { joined.contains(it, ignoreCase = true) }
            val communityParent = communityParentPatterns.any { joined.contains(it, ignoreCase = true) }
            val activity = labels.drop(1).firstOrNull { looksLikeActivityLabel(it) }
            val candidate = GroupSyncCandidate(
                name = title,
                unreadCount = unread,
                activityText = activity,
                active = !inactive,
                publishableHint = !inactive && !communityParent,
                communityParentHint = communityParent
            )
            val key = normalizeGroupIdentity(title)
            val previous = byName[key]
            byName[key] = if (previous == null) candidate else previous.copy(
                unreadCount = maxOf(previous.unreadCount, candidate.unreadCount),
                activityText = previous.activityText ?: candidate.activityText,
                active = previous.active || candidate.active,
                publishableHint = previous.publishableHint || candidate.publishableHint,
                communityParentHint = previous.communityParentHint || candidate.communityParentHint
            )
        }

        // Primary path: discover a visible text/description node then climb to the real wide row.
        // This fixes WhatsApp versions where neither the title TextView nor the RecyclerView row
        // itself is marked clickable; often only an intermediate parent owns ACTION_CLICK.
        walk(listRoot) { node ->
            if (byName.size >= limit || !node.isVisibleToUser) return@walk
            val raw = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .firstOrNull { looksLikeConversationTitle(it) } ?: return@walk
            if (raw.isBlank()) return@walk
            var current: AccessibilityNodeInfo? = node
            repeat(9) {
                val c = current ?: return@repeat
                val b = Rect().also(c::getBoundsInScreen)
                val wideEnough = b.width() >= minWidth && b.height() in 38..430
                if (wideEnough && c.isVisibleToUser && c.isEnabled) {
                    val clickable = nearestClickable(c)
                    acceptRow(clickable ?: c)
                    if (byName.size >= limit) return@walk
                }
                current = c.parent
            }
        }

        // Compatibility path for merged rows whose title exists only in contentDescription.
        if (byName.size < limit) {
            walk(listRoot) { node ->
                if (byName.size >= limit || !node.isVisibleToUser || !node.isEnabled) return@walk
                val b = Rect().also(node::getBoundsInScreen)
                if (b.width() >= minWidth && b.height() in 38..430) acceptRow(nearestClickable(node) ?: node)
            }
        }
        return byName.values.toSet()
    }

    fun collectChatListCandidates(root: AccessibilityNodeInfo?): Set<String> =
        collectChatListCandidatesDetailed(root).mapTo(linkedSetOf()) { it.name }

    /**
     * Opens an exact conversation row already visible in the main chat list without touching Search.
     * The click target is the row containing the exact title, not an arbitrary text match elsewhere.
     */
    fun openVisibleChatListRow(root: AccessibilityNodeInfo?, exactName: String): Boolean {
        val row = findVisibleChatListRow(root, exactName) ?: return false
        if (row.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        return clickNodeOrParent(row)
    }

    fun visibleChatListRowBounds(root: AccessibilityNodeInfo?, exactName: String): Rect? =
        findVisibleChatListRow(root, exactName)?.let { node -> Rect().also(node::getBoundsInScreen) }

    /**
     * WhatsApp builds do not always expose timestamp/unread text in the same accessibility subtree.
     * v2.14 required those secondary labels and therefore rejected perfectly valid visible rows.
     * The runtime fix anchors on the exact title, then accepts only a wide clickable row in the
     * conversation-list region. The toolbar/header area is excluded to avoid clicking the chat title.
     */
    fun findVisibleChatListRow(root: AccessibilityNodeInfo?, exactName: String): AccessibilityNodeInfo? {
        if (root == null || exactName.isBlank()) return null
        val listRoot = findChatListContainer(root) ?: root
        val listBounds = Rect().also(listRoot::getBoundsInScreen)
        val window = Rect().also(root::getBoundsInScreen)
        val minWidth = (window.width() * 0.48f).toInt().coerceAtLeast(1)
        var bestRow: AccessibilityNodeInfo? = null
        var bestTop = Int.MAX_VALUE

        // First match the visible title node, then climb to its clickable/wide row. This handles
        // WhatsApp builds where the TextView itself is not clickable but its parent container is.
        walk(listRoot) { node ->
            if (!node.isVisibleToUser) return@walk
            val labels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            if (labels.none { groupNamesEquivalent(it, exactName) }) return@walk
            var candidate: AccessibilityNodeInfo? = node
            repeat(7) {
                val c = candidate ?: return@repeat
                val rect = Rect().also(c::getBoundsInScreen)
                val validGeometry = Rect.intersects(listBounds, rect) && rect.height() in 40..380 && rect.width() >= minWidth
                if (c.isVisibleToUser && c.isEnabled && validGeometry && (c.isClickable || hasClickableSelfOrAncestor(c))) {
                    val clickable = nearestClickable(c) ?: c
                    val clickRect = Rect().also(clickable::getBoundsInScreen)
                    if (clickRect.top < bestTop) {
                        bestTop = clickRect.top
                        bestRow = clickable
                    }
                    return@walk
                }
                candidate = c.parent
            }
        }

        if (bestRow != null) return bestRow

        // Compatibility fallback for merged accessibility rows that expose title only through row parsing.
        walk(listRoot) { node ->
            if (!node.isVisibleToUser || !node.isEnabled) return@walk
            val rect = Rect().also(node::getBoundsInScreen)
            if (!Rect.intersects(listBounds, rect) || rect.height() !in 40..380 || rect.width() < minWidth) return@walk
            val title = conversationTitleFromRow(node) ?: return@walk
            if (!groupNamesEquivalent(title, exactName)) return@walk
            val clickable = nearestClickable(node) ?: if (node.isClickable) node else null
            if (clickable != null && rect.top < bestTop) {
                bestTop = rect.top
                bestRow = clickable
            }
        }
        return bestRow
    }

    private fun currentChatHeaderNode(root: AccessibilityNodeInfo?, groupName: String): AccessibilityNodeInfo? {
        if (root == null || groupName.isBlank()) return null
        val window = Rect().also(root::getBoundsInScreen)
        val headerBottom = window.top + (window.height() * 0.28f).toInt()
        var best: AccessibilityNodeInfo? = null
        var bestTop = Int.MAX_VALUE
        walk(root) { node ->
            if (!node.isVisibleToUser) return@walk
            val labels = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            if (labels.none { groupNamesEquivalent(it, groupName) }) return@walk
            val rect = Rect().also(node::getBoundsInScreen)
            if (rect.top <= headerBottom && rect.width() > 0 && rect.height() > 0 && rect.top < bestTop) {
                bestTop = rect.top
                best = node
            }
        }
        return best
    }

    fun currentChatHeaderBounds(root: AccessibilityNodeInfo?, groupName: String): Rect? =
        currentChatHeaderNode(root, groupName)?.let { Rect().also(it::getBoundsInScreen) }

    private fun findInviteActionNode(root: AccessibilityNodeInfo?, approval: Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        val ids = if (approval) inviteRequestIds else inviteJoinIds
        findByKnownIds(root, ids)?.let { node ->
            if (node.isVisibleToUser && node.isEnabled && hasClickableSelfOrAncestor(node)) return node
        }
        val labels = if (approval) inviteRequestLabels else inviteJoinLabels
        return findVisibleNodeByPatterns(root, labels)
            ?.takeIf { it.isVisibleToUser && it.isEnabled && hasClickableSelfOrAncestor(it) }
    }

    fun inviteActionAvailable(root: AccessibilityNodeInfo?, approval: Boolean): Boolean =
        findInviteActionNode(root, approval) != null

    fun clickInviteAction(root: AccessibilityNodeInfo?, approval: Boolean): Boolean {
        val node = findInviteActionNode(root, approval) ?: return false
        return clickNodeOrParent(node)
    }

    private fun findInviteConfirmationNode(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        findByKnownIds(root, inviteConfirmationIds)?.let { node ->
            if (node.isVisibleToUser && node.isEnabled && hasClickableSelfOrAncestor(node)) return node
        }
        return findVisibleNodeByPatterns(root, inviteConfirmationLabels)
            ?.takeIf { it.isVisibleToUser && it.isEnabled && hasClickableSelfOrAncestor(it) }
    }

    fun inviteConfirmationAvailable(root: AccessibilityNodeInfo?): Boolean =
        findInviteConfirmationNode(root) != null

    fun clickInviteConfirmation(root: AccessibilityNodeInfo?): Boolean {
        val node = findInviteConfirmationNode(root) ?: return false
        return clickNodeOrParent(node)
    }

    fun clickInviteClose(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        findByKnownIds(root, inviteCloseIds)?.let { node ->
            if (node.isVisibleToUser && node.isEnabled && clickNodeOrParent(node)) return true
        }
        val node = findVisibleNodeByPatterns(root, inviteCloseLabels) ?: return false
        return node.isEnabled && hasClickableSelfOrAncestor(node) && clickNodeOrParent(node)
    }

    fun scrollChatListForward(root: AccessibilityNodeInfo?): Boolean {
        val list = findChatListContainer(root) ?: return false
        return list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    fun scrollChatListBackward(root: AccessibilityNodeInfo?): Boolean {
        val list = findChatListContainer(root) ?: return false
        return list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    fun openArchived(root: AccessibilityNodeInfo?): Boolean {
        val node = findVisibleNodeByPatterns(root, archivedPatterns) ?: return false
        return clickNodeOrParent(node)
    }

    /**
     * Conservative publish blocker classification. Returns a terminal PublishStatus only when the
     * current WhatsApp tree exposes an explicit signal. Otherwise null means "continue normal flow".
     */
    fun classifyPublishBlocker(root: AccessibilityNodeInfo?): com.althmany.extractor.data.PublishStatus? {
        if (root == null) return com.althmany.extractor.data.PublishStatus.UI_ERROR
        val labels = buildList {
            walk(root) { node ->
                node.text?.toString()?.takeIf { it.isNotBlank() }?.let { add(it) }
                node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { add(it) }
                node.hintText?.toString()?.takeIf { it.isNotBlank() }?.let { add(it) }
                node.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }.joinToString(" | ").lowercase()
        if (listOf("read_only_chat_info", "only admins can send", "only admins", "المشرفون فقط", "للمشرفين فقط", "للقراءة فقط").any(labels::contains)) {
            return com.althmany.extractor.data.PublishStatus.READ_ONLY
        }
        if (listOf("you were removed", "you are no longer a participant", "you left", "تمت إزالتك", "تمت ازالتك", "لم تعد مشارك", "غادرت المجموعة").any(labels::contains)) {
            return com.althmany.extractor.data.PublishStatus.LEFT
        }
        if (listOf("unblock_button_blocked_chat_footer", "blocked", "محظور", "إلغاء الحظر", "الغاء الحظر").any(labels::contains)) {
            return com.althmany.extractor.data.PublishStatus.BLOCKED
        }
        return null
    }

    fun setMessageComposerText(root: AccessibilityNodeInfo?, message: String): Boolean {
        if (root == null || message.isBlank()) return false
        val target = findMessageComposer(root) ?: return false
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        }
        if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        // Some WhatsApp variants expose SET_TEXT only after a fresh focused node is fetched.
        val fresh = findMessageComposer(root) ?: return false
        fresh.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return fresh.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun messageComposerContains(root: AccessibilityNodeInfo?, message: String): Boolean {
        val node = findMessageComposer(root) ?: return false
        return node.text?.toString()?.replace("\r\n", "\n") == message.replace("\r\n", "\n")
    }

    fun clickSendButton(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        findByKnownIds(root, listOf("send", "send_button", "send_btn"))?.let { known ->
            if (known.isVisibleToUser && clickNodeOrParent(known)) return true
        }
        val labels = listOf("إرسال", "Send")
        val window = Rect().also(root::getBoundsInScreen)
        val minTop = window.top + (window.height() * 0.50f).toInt()
        var candidate: AccessibilityNodeInfo? = null
        walk(root) { node ->
            if (candidate != null || !node.isVisibleToUser) return@walk
            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds.top < minTop) return@walk
            val label = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ")
            if (labels.any { label.trim().equals(it, true) || label.contains(it, true) } && hasClickableSelfOrAncestor(node)) {
                candidate = node
            }
        }
        return candidate?.let(::clickNodeOrParent) == true
    }

    private fun findMessageComposer(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        findByKnownIds(root, listOf("entry", "message", "compose_edit_text", "conversation_entry"))?.let { known ->
            if (known.isVisibleToUser && known.isEditable) return known
        }
        val window = Rect().also(root::getBoundsInScreen)
        val minTop = window.top + (window.height() * 0.48f).toInt()
        var candidate: AccessibilityNodeInfo? = null
        var bestBottom = Int.MIN_VALUE
        walk(root) { node ->
            if (!node.isVisibleToUser || !node.isEditable || !node.isEnabled) return@walk
            val bounds = Rect().also(node::getBoundsInScreen)
            val labels = listOfNotNull(node.text?.toString(), node.hintText?.toString(), node.contentDescription?.toString()).joinToString(" ")
            if (searchLabels.any { labels.contains(it, true) }) return@walk
            if (bounds.top >= minTop && bounds.bottom > bestBottom) {
                bestBottom = bounds.bottom
                candidate = node
            }
        }
        return candidate
    }

    private fun findByKnownIds(root: AccessibilityNodeInfo?, shortIds: List<String>): AccessibilityNodeInfo? {
        if (root == null) return null
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg.isBlank()) return null
        for (short in shortIds) {
            val full = "$pkg:id/$short"
            val found = runCatching { root.findAccessibilityNodeInfosByViewId(full) }.getOrNull().orEmpty()
                .firstOrNull { it.isVisibleToUser }
            if (found != null) return found
        }
        return null
    }

    fun clickPositiveShareAction(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val labels = listOf("إرسال", "Send", "التالي", "Next", "تم", "Done", "مشاركة", "Share")
        val window = Rect().also(root::getBoundsInScreen)
        val minTop = window.top + (window.height() * 0.40f).toInt()
        var candidate: AccessibilityNodeInfo? = null
        walk(root) { node ->
            if (candidate != null || !node.isVisibleToUser) return@walk
            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds.top < minTop) return@walk
            val label = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ").trim()
            if (labels.any { label.equals(it, true) || label.contains(it, true) } && hasClickableSelfOrAncestor(node)) {
                candidate = node
            }
        }
        return candidate?.let(::clickNodeOrParent) == true
    }

    fun visibleNonEditableExactText(root: AccessibilityNodeInfo?, expected: String): Boolean {
        if (root == null || expected.isBlank()) return false
        var found = false
        walk(root) { node ->
            if (found || !node.isVisibleToUser || node.isEditable) return@walk
            if (node.text?.toString()?.trim() == expected.trim()) found = true
        }
        return found
    }

    private fun findVisibleFilterChip(root: AccessibilityNodeInfo?, labels: List<String>): AccessibilityNodeInfo? {
        if (root == null) return null
        val window = Rect().also(root::getBoundsInScreen)
        val maxTop = window.top + (window.height() * 0.42f).toInt()
        var best: AccessibilityNodeInfo? = null
        var bestTop = Int.MAX_VALUE
        walk(root) { node ->
            if (!node.isVisibleToUser || !node.isEnabled) return@walk
            val value = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .joinToString(" ").trim()
            if (labels.none { value.equals(it, true) }) return@walk
            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds.top > maxTop || bounds.height() !in 24..220 || bounds.width() <= 0 ||
                bounds.width() > window.width() * 0.48f) return@walk
            if (!hasClickableSelfOrAncestor(node)) return@walk
            if (bounds.top < bestTop) { bestTop = bounds.top; best = node }
        }
        return best
    }

    /** The main chat RecyclerView/list only; message/info/media scroll containers are excluded. */
    private fun findChatListContainer(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null || isGroupInfoScreen(root)) return null
        val window = Rect().also(root::getBoundsInScreen)
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        walk(root) { node ->
            if (!node.isVisibleToUser || !node.isScrollable) return@walk
            val rect = Rect().also(node::getBoundsInScreen)
            if (rect.width() < window.width() * 0.55f || rect.height() < window.height() * 0.28f) return@walk
            if (rect.top < window.top + window.height() * 0.08f) return@walk
            candidates += node
        }
        return candidates.maxByOrNull { node ->
            val rect = Rect().also(node::getBoundsInScreen)
            val cls = node.className?.toString().orEmpty()
            val listBonus = if (cls.contains("RecyclerView", true) || cls.contains("List", true)) 2_000_000_000L else 0L
            val filterBonus = if (findVisibleFilterChip(root, chatFilterLabels) != null) 700_000_000L else 0L
            rect.width().toLong() * rect.height().toLong() + listBonus + filterBonus
        }
    }

    private fun findBestScrollable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        walk(root) { node -> if (node.isScrollable && node.isVisibleToUser) candidates.add(node) }
        return candidates.maxByOrNull { node ->
            val rect = Rect(); node.getBoundsInScreen(rect)
            val area = rect.width().toLong() * rect.height().toLong()
            val cls = node.className?.toString().orEmpty()
            val bonus = when {
                cls.contains("RecyclerView", true) -> 3_000_000_000L
                cls.contains("List", true) -> 2_000_000_000L
                else -> 0L
            }
            area + bonus
        }
    }

    private fun findVisibleNodeByPatterns(root: AccessibilityNodeInfo?, patterns: List<String>): AccessibilityNodeInfo? {
        if (root == null) return null
        for (pattern in patterns) {
            root.findAccessibilityNodeInfosByText(pattern).firstOrNull { node ->
                if (!node.isVisibleToUser) false else {
                    val t = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ")
                    t.contains(pattern, ignoreCase = true)
                }
            }?.let { return it }
        }
        return null
    }

    private fun selectedOrCheckedInChain(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            val n = current ?: return false
            if (n.isSelected || n.isChecked) return true
            current = n.parent
        }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(8) {
            val n = current ?: return false
            if (n.isClickable && n.isEnabled && n.isVisibleToUser) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = n.parent
        }
        return false
    }

    private fun hasClickableSelfOrAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(8) {
            val n = current ?: return false
            if (n.isClickable && n.isEnabled && n.isVisibleToUser) return true
            if (n.isScrollable) return false
            current = n.parent
        }
        return false
    }

    private fun hasClickableDescendant(node: AccessibilityNodeInfo): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(node)
        var seen = 0
        while (stack.isNotEmpty() && seen++ < 40) {
            val n = stack.removeLast()
            if (n !== node && (n.isClickable || n.isLongClickable || n.isEditable)) return true
            for (i in 0 until n.childCount) n.getChild(i)?.let(stack::add)
        }
        return false
    }

    private fun hasInteractiveAncestor(node: AccessibilityNodeInfo): Boolean {
        var parent = node.parent
        var depth = 0
        while (parent != null && depth++ < 7) {
            if (parent.isClickable || parent.isLongClickable || parent.isEditable) return true
            // Stop climbing once we hit a large scroll container. It can itself be scrollable while
            // the child is a legitimate non-interactive WhatsApp system status card.
            if (parent.isScrollable) break
            parent = parent.parent
        }
        return false
    }

    private val inactiveConversationPatterns = listOf(
        "تمت إزالتك", "تمت ازالتك", "لم تعد مشارك", "غادرت المجموعة",
        "you were removed", "you are no longer a participant", "you left"
    )

    private val communityParentPatterns = listOf(
        "إعلان المجتمع", "مجتمع", "community announcement", "community"
    )

    private fun conversationTitleFromRow(row: AccessibilityNodeInfo): String? {
        val rowBounds = Rect().also(row::getBoundsInScreen)
        val titleBandBottom = rowBounds.top + (rowBounds.height() * 0.62f).toInt()
        val textCandidates = mutableListOf<Pair<String, Rect>>()
        val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(row)
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 80) {
            val n = stack.removeLast()
            if (n.isVisibleToUser) {
                n.text?.toString()?.trim()?.takeIf { value ->
                    value.isNotEmpty() && looksLikeConversationTitle(value) && !looksLikeActivityLabel(value)
                }?.let { value ->
                    val rect = Rect().also(n::getBoundsInScreen)
                    if (rect.top <= titleBandBottom && rect.width() > 0 && rect.height() > 0) textCandidates += value to rect
                }
            }
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let(stack::add)
        }
        textCandidates.minWithOrNull(compareBy<Pair<String, Rect>> { it.second.top }.thenByDescending { it.second.height() })
            ?.first?.let { return it }

        // Merged accessibility rows sometimes expose only one combined description. In that case
        // use the first short segment instead of saving the entire preview/time string as a group name.
        val merged = row.contentDescription?.toString()?.trim().orEmpty()
        if (merged.isNotBlank()) {
            val first = merged.split('\n', ',', '،', '·').map(String::trim)
                .firstOrNull { it.isNotBlank() && looksLikeConversationTitle(it) && !looksLikeActivityLabel(it) }
            if (first != null) return first
        }
        return null
    }

    private fun descendantLabels(node: AccessibilityNodeInfo, limit: Int): List<String> {
        val out = mutableListOf<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(node)
        while (stack.isNotEmpty() && out.size < limit) {
            val n = stack.removeLast()
            n.text?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(out::add)
            n.contentDescription?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(out::add)
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let(stack::add)
        }
        return out.distinct()
    }

    private fun parseUnreadCount(labels: String): Int {
        val hasUnread = listOf("غير مقرو", "unread").any { labels.contains(it, ignoreCase = true) }
        if (!hasUnread) return 0
        val latinized = labels
            .replace('٠','0').replace('١','1').replace('٢','2').replace('٣','3').replace('٤','4')
            .replace('٥','5').replace('٦','6').replace('٧','7').replace('٨','8').replace('٩','9')
            .replace('۰','0').replace('۱','1').replace('۲','2').replace('۳','3').replace('۴','4')
            .replace('۵','5').replace('۶','6').replace('۷','7').replace('۸','8').replace('۹','9')
        return Regex("\\b(\\d{1,4})\\b").findAll(latinized).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 1
    }


    private fun groupNamesEquivalent(a: String, b: String): Boolean = normalizeGroupIdentity(a) == normalizeGroupIdentity(b)

    private fun normalizeGroupIdentity(value: String): String {
        if (value.isBlank()) return ""
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("[\u200B-\u200F\u202A-\u202E\u2060-\u206F\uFEFF]"), "")
            .replace(Regex("[\u064B-\u065F\u0670\u06D6-\u06ED\uFE00-\uFE0F]"), "")
            .replace('ـ', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.ROOT)
        return normalized
    }

    private fun nearestClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(8) {
            val c = current ?: return null
            if (c.isVisibleToUser && c.isEnabled && c.isClickable) return c
            current = c.parent
        }
        return null
    }

    private fun looksLikeActivityLabel(value: String): Boolean {
        val s = value.trim()
        if (s.isBlank() || s.length > 80) return false
        if (s.contains("unread", true) || s.contains("غير مقرو", true)) return false
        return s.matches(Regex(".*([0-9٠-٩۰-۹]{1,2}:[0-9٠-٩۰-۹]{2}|اليوم|أمس|امس|today|yesterday|ص|م|AM|PM).*", RegexOption.IGNORE_CASE))
    }

    private fun descendantTexts(node: AccessibilityNodeInfo, limit: Int): List<String> {
        val out = mutableListOf<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(node)
        while (stack.isNotEmpty() && out.size < limit) {
            val n = stack.removeLast()
            n.text?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(out::add)
            for (i in n.childCount - 1 downTo 0) n.getChild(i)?.let(stack::add)
        }
        return out.distinct()
    }

    private fun looksLikeConversationTitle(value: String): Boolean {
        val s = value.trim().replace(Regex("\\s+"), " ")
        if (s.length !in 1..120 || s.contains("http://", true) || s.contains("https://", true)) return false
        if (s.matches(Regex("^[0-9٠-٩۰-۹:./\\- ]+$"))) return false
        val exactExcluded = setOf(
            "واتساب", "whatsapp", "الدردشات", "chats", "التحديثات", "updates", "المجتمعات", "communities",
            "المكالمات", "calls", "بحث", "search", "مؤرشفة", "المؤرشفة", "archived", "رسالة", "message",
            "الحالة", "status", "الكل", "all", "غير مقروءة", "غير المقروءة", "unread", "المفضلة", "favorites",
            "المجموعات", "groups", "اسأل meta ai أو ابحث", "ask meta ai or search", "مزامنة جهات الاتصال", "sync contacts"
        )
        if (s.lowercase(Locale.ROOT) in exactExcluded) return false
        val systemContains = listOf(
            "جهات اتصالك غير متزامنة", "لا تتمكن من العثور على جهات اتصالك", "مراجعة جهات الاتصال",
            "your contacts aren't synced", "your contacts are not synced", "review your contacts",
            "رسائلك الشخصية مشفرة تمامًا بين الطرفين", "end-to-end encrypted",
            "اضغط لبدء دردشة", "tap to start a chat"
        )
        return systemContains.none { s.contains(it, true) }
    }

    private fun looksLikeContentToken(value: String): Boolean {
        val s = value.trim()
        if (s.length < 3) return false
        val excluded = listOf("بحث", "Search", "إرسال", "Send", "مكالمة", "Call", "فيديو", "Video", "رجوع", "Back")
        return excluded.none { s.equals(it, true) }
    }

    private fun normalizeToken(value: String): String = value.trim().replace(Regex("\\s+"), " ").take(240)

    private fun buildAnchorTokens(tokens: List<String>): List<String> {
        if (tokens.isEmpty()) return emptyList()
        val meaningful = tokens.filter { it.length >= 6 && !it.matches(Regex("^[0-9٠-٩۰-۹:./\\- ]+$")) }
        if (meaningful.size <= 6) return meaningful
        return (meaningful.take(3) + meaningful.takeLast(3)).distinct()
    }

    private fun nodeDepth(node: AccessibilityNodeInfo): Int {
        var depth = 0; var p = node.parent
        while (p != null && depth < 30) { depth++; p = p.parent }
        return depth
    }

    private inline fun walk(root: AccessibilityNodeInfo, crossinline action: (AccessibilityNodeInfo) -> Unit) {
        val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(root)
        var visited = 0
        while (stack.isNotEmpty() && visited++ < 6_000) {
            val node = stack.removeLast(); action(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(stack::add)
        }
    }
}
