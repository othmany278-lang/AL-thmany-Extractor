package com.althmany.extractor.shizuku

import android.content.Context
import android.graphics.Rect
import com.althmany.extractor.data.GroupSyncCandidate
import com.althmany.extractor.engine.LinkExtractor
import com.althmany.extractor.engine.NodeSnapshot
import java.util.Locale
import java.text.Normalizer

internal data class ShizukuUiNode(
    val index: Int,
    val parent: Int,
    val depth: Int,
    val text: String,
    val description: String,
    val viewId: String,
    val className: String,
    val packageName: String,
    val clickable: Boolean,
    val enabled: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val bounds: Rect
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()
    val label: String get() = listOf(text, description).filter(String::isNotBlank).joinToString(" ")
}

internal data class ShizukuUiTree(
    val state: String,
    val detail: String,
    val rootPackage: String,
    val nodes: List<ShizukuUiNode>
) {
    val width: Int get() = nodes.maxOfOrNull { it.bounds.right }?.coerceAtLeast(1) ?: 1080
    val height: Int get() = nodes.maxOfOrNull { it.bounds.bottom }?.coerceAtLeast(1) ?: 2400
    val texts: List<String> get() = nodes.asSequence().flatMap { sequenceOf(it.text, it.description) }.map(String::trim).filter(String::isNotBlank).distinct().toList()
    val signature: Int get() = texts.joinToString("\u0001").hashCode()
    val contentSignature: Int get() = texts.filter { it.length > 2 }.joinToString("\u0002").hashCode()
    val visibleNodeCount: Int get() = nodes.size
    val scrollableNodeFound: Boolean get() = nodes.any { it.scrollable }

    fun node(index: Int): ShizukuUiNode? = nodes.firstOrNull { it.index == index }
    fun ancestors(start: ShizukuUiNode, maxDepth: Int = 8): Sequence<ShizukuUiNode> = sequence {
        var current: ShizukuUiNode? = start
        var depth = 0
        while (current != null && depth++ < maxDepth) {
            yield(current)
            current = node(current.parent)
        }
    }
    fun descendants(parentIndex: Int, limit: Int = 40): List<ShizukuUiNode> {
        val out = ArrayList<ShizukuUiNode>(limit)
        val queue = ArrayDeque<Int>(); queue.add(parentIndex)
        while (queue.isNotEmpty() && out.size < limit) {
            val p = queue.removeFirst()
            nodes.filter { it.parent == p }.forEach { child -> out += child; queue += child.index }
        }
        return out
    }
}

/** High-level Shizuku UI adapter used only when probe proves the target UI is visible. */
internal class ShizukuUiRuntime(private val context: Context) {
    private val groupInfoPatterns = listOf(
        "مغادرة المجموعة", "إضافة أعضاء", "إضافة مشاركين", "دعوة عبر رابط", "أذونات المجموعة", "إعدادات المجموعة",
        "Exit group", "Add members", "Add participants", "Invite via link", "Group permissions", "Group settings"
    )
    private val groupsFilterPatterns = listOf("المجموعات", "مجموعات", "Groups")
    private val chatsTabPatterns = listOf("الدردشات", "دردشات", "Chats")
    private val archivedPatterns = listOf("مؤرشفة", "المؤرشفة", "Archived")
    private val mediaPatterns = listOf("الوسائط والروابط والمستندات", "الوسائط، الروابط والمستندات", "Media, links, and docs", "Media, links and docs", "Media, links & docs")
    private val linksPatterns = listOf("الروابط", "روابط", "Links")
    private val olderPatterns = listOf("مشاهدة الرسائل الأقدم", "تحميل الرسائل الأقدم", "Load older messages", "View older messages", "Tap here to load older messages")
    private val unreadPatterns = listOf("رسائل غير مقروءة", "Unread messages", "Unread message")
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
    private val chromeBlacklist = setOf(
        "الكل", "all", "غير مقروءة", "unread", "المفضلة", "favourites", "favorites", "المجموعات", "groups",
        "مؤرشفة", "archived", "المجتمعات", "communities", "الحالات", "updates", "المكالمات", "calls", "الدردشات", "chats",
        "meta ai", "بحث", "search", "الإعدادات", "settings", "مزامنة جهات الاتصال", "sync contacts", "واتساب", "whatsapp"
    )

    suspend fun snapshot(packageName: String): ShizukuUiTree = parse(ShizukuBridge.fastSnapshot(context, packageName))

    suspend fun waitFrame(packageName: String, sequence: Long, timeoutMs: Int): Pair<Long, ShizukuUiTree> {
        val frame = ShizukuBridge.waitAndSnapshot(context, packageName, sequence, timeoutMs, 1500)
        return frame.sequence to parse(frame.result)
    }

    suspend fun eventSequence(packageName: String): Long = ShizukuBridge.eventSequence(context, packageName)

    fun isWhatsApp(tree: ShizukuUiTree, packageName: String): Boolean = tree.rootPackage == packageName || tree.nodes.any { it.packageName == packageName }

    fun isGroupInfo(tree: ShizukuUiTree): Boolean = groupInfoPatterns.any { p -> tree.texts.any { it.contains(p, true) } }

    fun isGroupVisible(tree: ShizukuUiTree, name: String, packageName: String): Boolean {
        if (!isWhatsApp(tree, packageName) || isGroupInfo(tree)) return false
        val limit = (tree.height * 0.28f).toInt().coerceAtLeast(220)
        return tree.nodes.any { groupNamesEquivalent(it.text, name) && it.bounds.top <= limit }
    }

    /**
     * Confirmation used after an exact synced-row click. Some Samsung/WhatsApp builds hide the
     * conversation title from UIAutomation while still exposing the message composer/list.
     */
    fun isConversationOpenForTarget(tree: ShizukuUiTree, name: String, packageName: String): Boolean {
        if (!isWhatsApp(tree, packageName) || isGroupInfo(tree)) return false
        if (isGroupVisible(tree, name, packageName)) return true
        val minComposerTop = (tree.height * 0.52f).toInt()
        val hasComposer = tree.nodes.any { it.editable && it.enabled && it.bounds.top >= minComposerTop }
        if (hasComposer) return true
        val hasLargeScrollable = tree.nodes.any { it.scrollable && it.bounds.height() >= (tree.height * 0.28f).toInt() }
        val topLimit = (tree.height * 0.28f).toInt()
        val headerCandidate = tree.nodes.any { it.bounds.top <= topLimit && looksLikeConversationTitle(it.text.ifBlank { it.description }) }
        return hasLargeScrollable && headerCandidate
    }


    fun isConversationListVisible(tree: ShizukuUiTree, packageName: String): Boolean {
        if (!isWhatsApp(tree, packageName) || isGroupInfo(tree)) return false
        val minComposerTop = (tree.height * 0.52f).toInt()
        if (tree.nodes.any { it.editable && it.enabled && it.bounds.top >= minComposerTop }) return false
        val bottomStart = (tree.height * 0.70f).toInt()
        val navLabels = listOf("الدردشات", "دردشات", "Chats", "التحديثات", "Updates", "المجتمعات", "Communities", "المكالمات", "Calls")
        val bottomNav = tree.nodes.any { n ->
            n.bounds.top >= bottomStart && navLabels.any { p -> n.label.equals(p, true) || n.label.contains(p, true) }
        }
        val archived = tree.nodes.any { n -> archivedPatterns.any { p -> n.label.contains(p, true) } }
        return bottomNav || archived || collectChatCandidates(tree, packageName).isNotEmpty()
    }

    fun toNodeSnapshot(tree: ShizukuUiTree): NodeSnapshot {
        val tokens = tree.texts.map(::normalizeToken).filter { it.length >= 3 }.distinct()
        val anchors = if (tokens.size <= 8) tokens else tokens.take(4) + tokens.takeLast(4)
        return NodeSnapshot(tree.texts, tree.signature, tree.contentSignature, anchors, tree.scrollableNodeFound, tree.visibleNodeCount, tokens.size)
    }

    fun collectUrls(tree: ShizukuUiTree): Set<String> {
        val urls = linkedSetOf<String>()
        tree.nodes.forEach { node ->
            LinkExtractor.extract(node.text).forEach(urls::add)
            LinkExtractor.extract(node.description).forEach(urls::add)
        }
        return urls
    }

    suspend fun clickGroupsFilter(tree: ShizukuUiTree, packageName: String): Boolean {
        val maxTop = (tree.height * 0.38f).toInt()
        val node = tree.nodes.filter { it.bounds.top <= maxTop && groupsFilterPatterns.any { p -> it.label.trim().equals(p, true) } }
            .minByOrNull { it.bounds.top } ?: return false
        return clickNode(tree, node, packageName)
    }

    suspend fun clickChatsTab(tree: ShizukuUiTree, packageName: String): Boolean {
        val minTop = (tree.height * 0.66f).toInt()
        val node = tree.nodes.filter { it.bounds.top >= minTop && chatsTabPatterns.any { p -> it.label.contains(p, true) } }
            .maxByOrNull { it.bounds.bottom } ?: return false
        return clickNode(tree, node, packageName)
    }

    suspend fun openArchived(tree: ShizukuUiTree, packageName: String): Boolean {
        val node = tree.nodes.filter { n -> archivedPatterns.any { p -> n.label.contains(p, true) } }
            .minByOrNull { it.bounds.top } ?: return false
        return clickNode(tree, node, packageName)
    }

    fun collectChatCandidates(tree: ShizukuUiTree, packageName: String): Set<GroupSyncCandidate> {
        val out = linkedMapOf<String, GroupSyncCandidate>()
        val minTop = (tree.height * 0.08f).toInt()
        val maxBottom = (tree.height * 0.94f).toInt()
        val minWidth = (tree.width * 0.46f).toInt()
        val usedRows = hashSetOf<Int>()

        fun accept(row: ShizukuUiNode) {
            if (!usedRows.add(row.index)) return
            if (!row.enabled || row.packageName != packageName || row.bounds.top < minTop || row.bounds.bottom > maxBottom || row.bounds.height() !in 38..460 || row.bounds.width() < minWidth) return
            val labels = (listOf(row) + tree.descendants(row.index, 36))
                .flatMap { listOf(it.text, it.description) }.map(String::trim).filter(String::isNotBlank).distinct()
            val title = labels.firstOrNull { looksLikeConversationTitle(it) } ?: return
            val joined = labels.joinToString(" | ")
            val unread = parseUnreadCount(joined)
            val inactive = listOf("تمت إزالتك", "تمت ازالتك", "لم تعد مشارك", "غادرت المجموعة", "you were removed", "you left").any { joined.contains(it, true) }
            val community = listOf("إعلان المجتمع", "community announcement").any { joined.contains(it, true) }
            val activity = labels.drop(1).firstOrNull(::looksLikeActivityLabel)
            val candidate = GroupSyncCandidate(title, unread, activity, !inactive, !inactive && !community, community, whatsappPackage = packageName)
            val key = normalizeGroupName(title)
            val prev = out[key]
            out[key] = if (prev == null) candidate else prev.copy(
                unreadCount = maxOf(prev.unreadCount, candidate.unreadCount),
                activityText = prev.activityText ?: candidate.activityText,
                active = prev.active || candidate.active,
                publishableHint = prev.publishableHint || candidate.publishableHint,
                communityParentHint = prev.communityParentHint || candidate.communityParentHint
            )
        }

        // Start from title-like nodes and climb to the wide chat row. This survives UIAutomation
        // dumps where the row itself is not marked clickable.
        tree.nodes.asSequence()
            .filter { it.packageName == packageName && it.bounds.top >= minTop && it.bounds.bottom <= maxBottom }
            .filter { looksLikeConversationTitle(it.text.ifBlank { it.description }) }
            .forEach { node ->
                val row = tree.ancestors(node, 10)
                    .filter { it.enabled && it.bounds.width() >= minWidth && it.bounds.height() in 38..460 }
                    .sortedWith(compareByDescending<ShizukuUiNode> { it.clickable }.thenByDescending { it.bounds.width() })
                    .firstOrNull() ?: node
                accept(row)
            }

        // Fallback for merged rows where title exists only on the parent description.
        tree.nodes.asSequence()
            .filter { it.packageName == packageName && it.enabled && it.bounds.width() >= minWidth && it.bounds.height() in 38..460 }
            .forEach(::accept)
        return out.values.toSet()
    }

    suspend fun openVisibleChat(tree: ShizukuUiTree, name: String, packageName: String): Boolean {
        val exact = tree.nodes.filter { groupNamesEquivalent(it.text, name) }
        for (n in exact.sortedBy { it.bounds.top }) {
            val row = tree.ancestors(n).firstOrNull { it.clickable && it.enabled && it.bounds.height() in 44..430 } ?: n
            if (clickNode(tree, row, packageName)) return true
        }
        return false
    }

    suspend fun clickHeader(tree: ShizukuUiTree, name: String, packageName: String): Boolean {
        val maxTop = (tree.height * 0.28f).toInt()
        val n = tree.nodes.filter { groupNamesEquivalent(it.text, name) && it.bounds.top <= maxTop }.minByOrNull { it.bounds.top } ?: return false
        return clickNode(tree, n, packageName)
    }

    suspend fun clickSearch(tree: ShizukuUiTree, packageName: String): Boolean = clickPattern(tree, packageName, listOf("بحث", "Search"), preferTop = true)
    suspend fun setSearchText(packageName: String, text: String): Boolean = ShizukuBridge.fastSetEditableText(context, packageName, text, preferBottom = false)
    suspend fun setComposerText(packageName: String, text: String): Boolean = ShizukuBridge.fastSetEditableText(context, packageName, text, preferBottom = true)

    fun composerContains(tree: ShizukuUiTree, text: String): Boolean {
        val minTop = (tree.height * 0.52f).toInt()
        return tree.nodes.any { it.editable && it.bounds.top >= minTop && it.text == text }
    }

    fun visibleExactNonEditable(tree: ShizukuUiTree, text: String): Boolean = tree.nodes.any { !it.editable && it.text.trim() == text.trim() }

    suspend fun clickSend(tree: ShizukuUiTree, packageName: String): Boolean = clickPattern(tree, packageName, listOf("إرسال", "Send"), preferBottom = true)
    suspend fun clickPositiveAction(tree: ShizukuUiTree, packageName: String): Boolean = clickPattern(tree, packageName, listOf("إرسال", "Send", "التالي", "Next", "تم", "Done", "مشاركة", "Share"), preferBottom = true)
    private fun findInviteAction(tree: ShizukuUiTree, approval: Boolean): ShizukuUiNode? {
        val labels = if (approval) inviteRequestLabels else inviteJoinLabels
        val ids = if (approval) inviteRequestIds else inviteJoinIds
        return tree.nodes.filter { it.enabled && (
            labels.any { p -> it.label.contains(p, true) } ||
                ids.any { id -> it.viewId.contains(id, true) }
        ) }.maxByOrNull { it.bounds.bottom }
    }

    suspend fun clickInviteAction(tree: ShizukuUiTree, packageName: String, approval: Boolean): Boolean {
        val node = findInviteAction(tree, approval) ?: return false
        return clickNode(tree, node, packageName)
    }

    fun inviteActionAvailable(tree: ShizukuUiTree, approval: Boolean): Boolean =
        findInviteAction(tree, approval) != null

    private fun findInviteConfirmation(tree: ShizukuUiTree): ShizukuUiNode? =
        tree.nodes.filter { it.enabled && (
            inviteConfirmationLabels.any { p -> it.label.contains(p, true) } ||
                inviteConfirmationIds.any { id -> it.viewId.contains(id, true) }
        ) }.maxByOrNull { it.bounds.bottom }

    fun inviteConfirmationAvailable(tree: ShizukuUiTree): Boolean =
        findInviteConfirmation(tree) != null

    suspend fun clickInviteConfirmation(tree: ShizukuUiTree, packageName: String): Boolean {
        val node = findInviteConfirmation(tree) ?: return false
        return clickNode(tree, node, packageName)
    }

    suspend fun clickInviteClose(tree: ShizukuUiTree, packageName: String): Boolean {
        val node = tree.nodes.filter { it.enabled && (
            inviteCloseLabels.any { p -> it.label.contains(p, true) } ||
                inviteCloseIds.any { id -> it.viewId.contains(id, true) }
        ) }.minByOrNull { it.bounds.top } ?: return false
        return clickNode(tree, node, packageName)
    }

    suspend fun shellClickInviteAction(packageName: String, approval: Boolean): Boolean {
        val labels = if (approval) inviteRequestLabels else inviteJoinLabels
        val ids = if (approval) inviteRequestIds else inviteJoinIds
        val action = ShizukuBridge.shellFindUiAction(context, packageName, labels, ids)
        return action.found && ShizukuBridge.profileSafeTap(context, packageName, action.x, action.y)
    }

    suspend fun shellClickInviteConfirmation(packageName: String): Boolean {
        val action = ShizukuBridge.shellFindUiAction(context, packageName, inviteConfirmationLabels, inviteConfirmationIds)
        return action.found && ShizukuBridge.profileSafeTap(context, packageName, action.x, action.y)
    }

    suspend fun openMediaLinks(tree: ShizukuUiTree, packageName: String): Boolean = clickPattern(tree, packageName, mediaPatterns, preferTop = false)
    suspend fun openLinksTab(tree: ShizukuUiTree, packageName: String): Boolean = clickPattern(tree, packageName, linksPatterns, preferTop = true)
    fun linksTabLooksOpen(tree: ShizukuUiTree): Boolean = linksPatterns.any { p -> tree.texts.any { it.trim().equals(p, true) } } && (tree.scrollableNodeFound || collectUrls(tree).isNotEmpty())
    fun olderLoaderVisible(tree: ShizukuUiTree): Boolean = findPattern(tree, olderPatterns) != null
    suspend fun clickOlderLoader(tree: ShizukuUiTree, packageName: String): Boolean = clickPattern(tree, packageName, olderPatterns, preferTop = true)
    fun unreadDividerVisible(tree: ShizukuUiTree): Boolean = findPattern(tree, unreadPatterns) != null

    suspend fun swipeOlder(tree: ShizukuUiTree, durationMs: Int): Boolean = ShizukuBridge.fastSwipe(context, (tree.width*0.52f).toInt(), (tree.height*0.34f).toInt(), (tree.width*0.52f).toInt(), (tree.height*0.78f).toInt(), durationMs)
    suspend fun swipeListForward(tree: ShizukuUiTree, durationMs: Int): Boolean = ShizukuBridge.fastSwipe(context, (tree.width*0.52f).toInt(), (tree.height*0.78f).toInt(), (tree.width*0.52f).toInt(), (tree.height*0.28f).toInt(), durationMs)
    suspend fun swipeListBackward(tree: ShizukuUiTree, durationMs: Int): Boolean = ShizukuBridge.fastSwipe(context, (tree.width*0.52f).toInt(), (tree.height*0.30f).toInt(), (tree.width*0.52f).toInt(), (tree.height*0.78f).toInt(), durationMs)
    suspend fun back(): Boolean = ShizukuBridge.fastBack(context)

    private suspend fun clickPattern(tree: ShizukuUiTree, packageName: String, patterns: List<String>, preferTop: Boolean = false, preferBottom: Boolean = false): Boolean {
        val matches = tree.nodes.filter { n -> patterns.any { p -> n.label.contains(p, true) } }
        val n = when { preferTop -> matches.minByOrNull { it.bounds.top }; preferBottom -> matches.maxByOrNull { it.bounds.bottom }; else -> matches.firstOrNull() } ?: return false
        return clickNode(tree, n, packageName)
    }

    private fun findPattern(tree: ShizukuUiTree, patterns: List<String>): ShizukuUiNode? = tree.nodes.firstOrNull { n -> patterns.any { p -> n.label.contains(p, true) } }

    private suspend fun clickNode(tree: ShizukuUiTree, node: ShizukuUiNode, packageName: String): Boolean {
        val candidate = tree.ancestors(node).firstOrNull { it.clickable && it.enabled } ?: node
        if (ShizukuBridge.fastClickNode(context, packageName, candidate.centerX, candidate.centerY)) return true
        if (ShizukuBridge.fastTap(context, candidate.centerX, candidate.centerY)) return true
        return ShizukuBridge.profileSafeTap(context, packageName, candidate.centerX, candidate.centerY)
    }

    private fun parse(result: ShizukuBridge.FastUiResult): ShizukuUiTree {
        val rootPkg = Regex("(?:^|;)pkg=([^;]+)").find(result.detail)?.groupValues?.getOrNull(1).orEmpty()
        val nodes = result.payload.lineSequence().drop(1).mapNotNull { line ->
            val f = splitEscaped(line)
            if (f.size < 14 || f[0] != "N") return@mapNotNull null
            val b = f[13].split(',').mapNotNull(String::toIntOrNull); if (b.size != 4) return@mapNotNull null
            ShizukuUiNode(f[1].toIntOrNull()?:0, f[2].toIntOrNull()?:-1, f[3].toIntOrNull()?:0, unescape(f[4]), unescape(f[5]), unescape(f[6]), unescape(f[7]), unescape(f[8]), f[9]=="1", f[10]=="1", f[11]=="1", f[12]=="1", Rect(b[0],b[1],b[2],b[3]))
        }.toList()
        return ShizukuUiTree(result.state, result.detail, rootPkg, nodes)
    }

    private fun splitEscaped(line: String): List<String> {
        val out=mutableListOf<String>(); val b=StringBuilder(); var slash=false
        for(c in line){ if(slash){b.append('\\').append(c);slash=false}else when(c){'\\'->slash=true;'\t'->{out+=b.toString();b.setLength(0)};else->b.append(c)} }; if(slash)b.append('\\');out+=b.toString();return out
    }
    private fun unescape(v:String):String = buildString { var i=0; while(i<v.length){ if(v[i]=='\\'&&i+1<v.length){ when(v[i+1]){'t'->append('\t');'n'->append('\n');'r'->append('\r');'\\'->append('\\');else->{append(v[i]);append(v[i+1])}};i+=2}else{append(v[i]);i++} } }
    private fun normalizeGroupName(value: String): String {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
            .replace(Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069\uFE0E\uFE0F]"), "")
            .replace(Regex("[\u064B-\u065F\u0670\u06D6-\u06ED]"), "")
            .replace('ـ', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.ROOT)
    }

    private fun groupNamesEquivalent(a: String, b: String): Boolean {
        val na = normalizeGroupName(a)
        val nb = normalizeGroupName(b)
        return na.isNotBlank() && na == nb
    }

    private fun normalizeToken(v:String)=v.trim().replace(Regex("\\s+")," ").lowercase(Locale.ROOT)
    private fun looksLikeConversationTitle(v:String):Boolean{val s=v.trim();if(s.length !in 1..120)return false;val n=normalizeToken(s);if(n in chromeBlacklist)return false;if(s.startsWith("http://",true)||s.startsWith("https://",true))return false;if(looksLikeActivityLabel(s))return false;return s.any{it.isLetterOrDigit()}}
    private fun parseUnreadCount(v:String):Int{if(!v.contains("unread",true)&&!v.contains("غير مقرو",true))return 0;val x=v.replace('٠','0').replace('١','1').replace('٢','2').replace('٣','3').replace('٤','4').replace('٥','5').replace('٦','6').replace('٧','7').replace('٨','8').replace('٩','9');return Regex("\\b(\\d{1,4})\\b").findAll(x).mapNotNull{it.groupValues[1].toIntOrNull()}.maxOrNull()?:1}
    private fun looksLikeActivityLabel(v:String):Boolean{val s=v.trim();if(s.isBlank()||s.length>80)return false;return s.matches(Regex(".*([0-9٠-٩]{1,2}:[0-9٠-٩]{2}|اليوم|أمس|امس|today|yesterday|ص|م|AM|PM).*",RegexOption.IGNORE_CASE))}
}
