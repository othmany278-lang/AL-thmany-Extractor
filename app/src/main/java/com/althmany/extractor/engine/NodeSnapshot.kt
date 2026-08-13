package com.althmany.extractor.engine

data class NodeSnapshot(
    val texts: List<String>,
    val signature: Int,
    val contentSignature: Int,
    val anchorTokens: List<String>,
    val scrollableNodeFound: Boolean,
    val visibleNodeCount: Int,
    val messageTokenCount: Int
) {
    fun matchesAnchor(anchor: List<String>): Boolean {
        if (anchor.isEmpty()) return false
        val current = anchorTokens.toSet()
        val wanted = anchor.filter { it.isNotBlank() }.toSet()
        if (wanted.isEmpty()) return false
        val overlap = wanted.count(current::contains)
        return overlap >= minOf(2, wanted.size)
    }
}
