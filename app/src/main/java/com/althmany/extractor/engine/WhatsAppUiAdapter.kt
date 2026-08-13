package com.althmany.extractor.engine

import android.graphics.Rect
import android.text.Spanned
import android.text.style.URLSpan
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.althmany.extractor.data.GroupSyncCandidate
import java.util.Locale

/**
 * WhatsApp-specific heuristics are isolated here. No database or job state is kept in this class.
 * That makes WhatsApp UI changes patchable without rewriting the extraction coordinator.
 */
class WhatsAppUiAdapter {
    private val searchLabels = listOf("بحث", "Search")
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
        val matches = root.findAccessibilityNodeInfosByText(exactName)
            .filter { it.isVisibleToUser }
            .sortedBy { nodeDepth(it) }
        val exact = matches.firstOrNull { it.text?.toString()?.trim()?.equals(exactName.trim(), true) == true }
            ?: matches.firstOrNull()
        return exact?.let(::clickNodeOrParent) == true
    }

    fun isGroupVisible(root: AccessibilityNodeInfo?, groupName: String, expectedPackage: String? = null): Boolean {
        if (root == null || !isWhatsAppRoot(root, expectedPackage)) return false
        // A plain text match is not enough: the group name can appear in an old message or a search
        // result. Accept it only when it looks like the live chat header near the top of the window.
        // Nested info/media screens are rejected explicitly so retries cannot "verify" the wrong page.
        if (isGroupInfoScreen(root)) return false
        val window = Rect().also(root::getBoundsInScreen)
        val height = window.height().coerceAtLeast(1)
        val headerLimit = window.top + maxOf(220, (height * 0.28f).toInt())
        return root.findAccessibilityNodeInfosByText(groupName).any { node ->
            if (!node.isVisibleToUser || node.text?.toString()?.trim()?.equals(groupName.trim(), true) != true) {
                false
            } else {
                val bounds = Rect().also(node::getBoundsInScreen)
                bounds.top <= headerLimit
            }
        }
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
        if (root == null) return false
        val matches = root.findAccessibilityNodeInfosByText(groupName).filter { it.isVisibleToUser }
        val topMost = matches.minByOrNull {
            val r = Rect(); it.getBoundsInScreen(r); r.top
        } ?: return false
        return clickNodeOrParent(topMost)
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
        val byName = linkedMapOf<String, GroupSyncCandidate>()
        walk(root) { node ->
            if (!node.isVisibleToUser) return@walk
            val row = when {
                node.isClickable -> node
                node.parent?.isClickable == true -> node.parent
                node.parent?.parent?.isClickable == true -> node.parent?.parent
                else -> null
            } ?: return@walk
            val bounds = Rect(); row.getBoundsInScreen(bounds)
            if (bounds.height() < 40 || bounds.height() > 420) return@walk
            val labels = descendantLabels(row, 32)
            val title = labels.firstOrNull(::looksLikeConversationTitle) ?: return@walk
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
            val previous = byName[title.lowercase(Locale.ROOT)]
            byName[title.lowercase(Locale.ROOT)] = if (previous == null) candidate else previous.copy(
                unreadCount = maxOf(previous.unreadCount, candidate.unreadCount),
                activityText = previous.activityText ?: candidate.activityText,
                active = previous.active || candidate.active,
                publishableHint = previous.publishableHint || candidate.publishableHint,
                communityParentHint = previous.communityParentHint || candidate.communityParentHint
            )
        }
        return byName.values.toSet()
    }

    fun collectChatListCandidates(root: AccessibilityNodeInfo?): Set<String> =
        collectChatListCandidatesDetailed(root).mapTo(linkedSetOf()) { it.name }

    fun scrollChatListForward(root: AccessibilityNodeInfo?): Boolean = scrollGenericForward(root)
    fun scrollChatListBackward(root: AccessibilityNodeInfo?): Boolean = scrollToOlderMessages(root)

    fun openArchived(root: AccessibilityNodeInfo?): Boolean {
        val node = findVisibleNodeByPatterns(root, archivedPatterns) ?: return false
        return clickNodeOrParent(node)
    }

    fun setMessageComposerText(root: AccessibilityNodeInfo?, message: String): Boolean {
        if (root == null || message.isBlank()) return false
        val window = Rect().also(root::getBoundsInScreen)
        val minTop = window.top + (window.height() * 0.55f).toInt()
        var candidate: AccessibilityNodeInfo? = null
        walk(root) { node ->
            if (candidate != null || !node.isVisibleToUser || !node.isEditable) return@walk
            val bounds = Rect().also(node::getBoundsInScreen)
            val text = listOfNotNull(node.text?.toString(), node.hintText?.toString(), node.contentDescription?.toString()).joinToString(" ")
            val looksSearch = searchLabels.any { text.contains(it, true) }
            if (bounds.top >= minTop && !looksSearch) candidate = node
        }
        val target = candidate ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message) }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun messageComposerContains(root: AccessibilityNodeInfo?, message: String): Boolean {
        if (root == null) return false
        val window = Rect().also(root::getBoundsInScreen)
        val minTop = window.top + (window.height() * 0.55f).toInt()
        var found = false
        walk(root) { node ->
            if (found || !node.isVisibleToUser || !node.isEditable) return@walk
            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds.top >= minTop && node.text?.toString() == message) found = true
        }
        return found
    }

    fun clickSendButton(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
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
        val s = value.trim()
        if (s.length !in 1..120 || s.contains("http://", true) || s.contains("https://", true)) return false
        if (s.matches(Regex("^[0-9٠-٩۰-۹:./\\- ]+$"))) return false
        val excluded = listOf(
            "واتساب", "WhatsApp", "الدردشات", "Chats", "التحديثات", "Updates", "المجتمعات", "Communities",
            "المكالمات", "Calls", "بحث", "Search", "مؤرشفة", "Archived", "رسالة", "Message", "الحالة", "Status"
        )
        return excluded.none { s.equals(it, true) }
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
