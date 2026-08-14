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
    private val mediaPatterns = listOf("الوسائط والروابط والمستندات", "الوسائط، الروابط والمستندات", "Media, links, and docs", "Media, links and docs", "Media, links & docs")
    private val linksPatterns = listOf("الروابط", "روابط", "Links")
    private val olderPatterns = listOf("مشاهدة الرسائل الأقدم", "تحميل الرسائل الأقدم", "Load older messages", "View older messages", "Tap here to load older messages")
    private val unreadPatterns = listOf("رسائل غير مقروءة", "Unread messages", "Unread message")
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
        val bottomStart = (tree.height * 0.72f).toInt()
        val navLabels = listOf("الدردشات", "Chats", "التحديثات", "Updates", "المجتمعات", "Communities", "المكالمات", "Calls")
        val bottomNav = tree.nodes.any { n ->
            n.bounds.top >= bottomStart && navLabels.any { p -> n.label.equals(p, true) || n.label.contains(p, true) }
        }
        return bottomNav || collectChatCandidates(tree, packageName).isNotEmpty()
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

    fun collectChatCandidates(tree: ShizukuUiTree, packageName: String): Set<GroupSyncCandidate> {
        val out = linkedMapOf<String, GroupSyncCandidate>()
        val minTop = (tree.height * 0.12f).toInt()
        val maxBottom = (tree.height * 0.94f).toInt()
        tree.nodes.filter { it.clickable && it.enabled && it.packageName == packageName && it.bounds.height() in 44..430 && it.bounds.top >= minTop && it.bounds.bottom <= maxBottom }
            .forEach { row ->
                val labels = (listOf(row) + tree.descendants(row.index, 30)).flatMap { listOf(it.text, it.description) }.map(String::trim).filter(String::isNotBlank).distinct()
                val title = labels.firstOrNull { looksLikeConversationTitle(it) } ?: return@forEach
                val joined = labels.joinToString(" | ")
                val unread = parseUnreadCount(joined)
                val inactive = listOf("تمت إزالتك", "لم تعد مشارك", "غادرت المجموعة", "you were removed", "you left").any { joined.contains(it, true) }
                val community = listOf("إعلان المجتمع", "community announcement").any { joined.contains(it, true) }
                val activity = labels.drop(1).firstOrNull(::looksLikeActivityLabel)
                val candidate = GroupSyncCandidate(title, unread, activity, !inactive, !inactive && !community, community, whatsappPackage = packageName)
                val key = title.lowercase(Locale.ROOT)
                val prev = out[key]
                out[key] = if (prev == null) candidate else prev.copy(unreadCount=maxOf(prev.unreadCount,candidate.unreadCount), activityText=prev.activityText?:candidate.activityText, active=prev.active||candidate.active, publishableHint=prev.publishableHint||candidate.publishableHint, communityParentHint=prev.communityParentHint||candidate.communityParentHint)
            }
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
    suspend fun clickInviteAction(tree: ShizukuUiTree, packageName: String, approval: Boolean): Boolean = clickPattern(tree, packageName, if (approval) listOf("طلب الانضمام", "إرسال طلب الانضمام", "Request to join", "Send request") else listOf("الانضمام إلى المجموعة", "انضم إلى المجموعة", "Join group", "Join this group"), preferBottom = true)
    fun inviteActionAvailable(tree: ShizukuUiTree, approval: Boolean): Boolean = findPattern(tree, if (approval) listOf("طلب الانضمام", "إرسال طلب الانضمام", "Request to join", "Send request") else listOf("الانضمام إلى المجموعة", "انضم إلى المجموعة", "Join group", "Join this group")) != null

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
        return ShizukuBridge.fastTap(context, candidate.centerX, candidate.centerY)
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
            .replace(Regex("\s+"), " ")
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
