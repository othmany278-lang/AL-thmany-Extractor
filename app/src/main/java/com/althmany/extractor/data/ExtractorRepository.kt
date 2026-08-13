package com.althmany.extractor.data

import com.althmany.extractor.engine.InviteLinkParser
import com.althmany.extractor.engine.ScanScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExtractorRepository(private val db: ExtractorDatabase) {
    suspend fun addGroupsFromText(text: String) = withContext(Dispatchers.IO) {
        db.replaceGroups(text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList())
    }
    suspend fun addDiscoveredGroups(names: Collection<String>): Int = withContext(Dispatchers.IO) { db.addDiscoveredGroups(names) }
    suspend fun groups(): List<TargetGroup> = withContext(Dispatchers.IO) { db.getGroups() }
    suspend fun pendingSelectedGroups(): List<TargetGroup> = withContext(Dispatchers.IO) { db.getSelectedPendingGroups() }
    suspend fun selectedGroups(): List<TargetGroup> = withContext(Dispatchers.IO) { db.getSelectedGroups() }
    suspend fun setSelected(id: Long, selected: Boolean) = withContext(Dispatchers.IO) { db.setGroupSelected(id, selected) }
    suspend fun setAllSelected(selected: Boolean) = withContext(Dispatchers.IO) { db.setAllGroupsSelected(selected) }
    suspend fun updateStatus(id: Long, status: GroupStatus, error: String? = null) = withContext(Dispatchers.IO) { db.updateGroupStatus(id, status, error) }
    suspend fun markVerifiedGroup(id: Long, verified: Boolean) = withContext(Dispatchers.IO) { db.markVerifiedGroup(id, verified) }
    suspend fun resetRunStatuses() = withContext(Dispatchers.IO) { db.resetRunStatuses() }
    suspend fun saveLink(url: String, normalized: String, groupName: String): Boolean = withContext(Dispatchers.IO) {
        db.upsertLink(url, normalized, groupName, System.currentTimeMillis())
    }
    suspend fun saveLinksBatch(links: List<Pair<String, String>>, groupName: String): Int = withContext(Dispatchers.IO) {
        db.upsertLinksBatch(links, groupName, System.currentTimeMillis())
    }
    suspend fun saveCheckpoint(checkpoint: GroupCheckpoint) = withContext(Dispatchers.IO) { db.saveCheckpoint(checkpoint) }
    suspend fun checkpoint(groupName: String): GroupCheckpoint? = withContext(Dispatchers.IO) { db.getCheckpoint(groupName) }
    suspend fun log(groupName: String?, level: String, code: String, message: String) = withContext(Dispatchers.IO) {
        db.log(groupName, level, code, message)
    }
    suspend fun logs(): List<ExtractionLog> = withContext(Dispatchers.IO) { db.getLogs() }
    suspend fun links(): List<LinkRecord> = withContext(Dispatchers.IO) { db.getLinks() }

    suspend fun addScanLinksFromText(text: String, sourceGroup: String? = null): Int = withContext(Dispatchers.IO) {
        val seeds = InviteLinkParser.extract(text).map { invite ->
            ScanSeed(invite.originalUrl, invite.normalizedUrl, invite.code, sourceGroup)
        }
        db.upsertScanItemsBatch(seeds, System.currentTimeMillis())
    }

    suspend fun importInviteLinksFromExtraction(): Int = withContext(Dispatchers.IO) {
        val deduped = linkedMapOf<String, ScanSeed>()
        db.getLinks().forEach { link ->
            InviteLinkParser.extract(link.url).forEach { invite ->
                deduped.putIfAbsent(
                    invite.normalizedUrl.lowercase(),
                    ScanSeed(invite.originalUrl, invite.normalizedUrl, invite.code, link.groupName)
                )
            }
        }
        db.upsertScanItemsBatch(deduped.values.toList(), System.currentTimeMillis())
    }

    suspend fun scanItems(): List<ScanRecord> = withContext(Dispatchers.IO) { db.getScanItems() }
    suspend fun prepareRecheckAll() = withContext(Dispatchers.IO) { db.resetAllScanItemsForRecheck() }

    suspend fun scanItemsForScope(scope: ScanScope): List<ScanRecord> = withContext(Dispatchers.IO) {
        when (scope) {
            ScanScope.PENDING_ONLY -> db.getPendingScanItems()
            ScanScope.UNCERTAIN_ONLY -> db.getUncertainScanItems()
            ScanScope.RECHECK_ALL -> db.getScanItems()
        }
    }
    suspend fun markScanAttempt(id: Long, detail: String, targetPackage: String?) = withContext(Dispatchers.IO) {
        db.markScanAttempt(id, detail, targetPackage)
    }

    suspend fun updateScanResult(
        id: Long,
        status: ScanStatus,
        groupName: String?,
        detail: String?,
        incrementAttempt: Boolean,
        confidence: Int = 0,
        memberCountText: String? = null,
        inviteKind: InviteKind = InviteKind.UNKNOWN,
        signalCode: String? = null,
        durationMs: Long? = null,
        targetPackage: String? = null
    ) = withContext(Dispatchers.IO) {
        db.updateScanResult(id, status, groupName, detail, incrementAttempt, confidence, memberCountText, inviteKind, signalCode, durationMs, targetPackage)
    }
    suspend fun resetScanRunningItems() = withContext(Dispatchers.IO) { db.resetScanRunningItems() }
    suspend fun clearScan() = withContext(Dispatchers.IO) { db.clearScanItems() }
    suspend fun scanStats(): ScanStats = withContext(Dispatchers.IO) { db.scanStats() }

    suspend fun stopResumablePublishRuns() = withContext(Dispatchers.IO) { db.stopResumablePublishRuns() }
    suspend fun createPublishRun(message: String, targetPackage: String, delayMs: Long, maxAttempts: Int, groupNames: List<String>): Long =
        withContext(Dispatchers.IO) { db.createPublishRun(message, targetPackage, delayMs, maxAttempts, groupNames) }
    suspend fun publishRun(id: Long): PublishRun? = withContext(Dispatchers.IO) { db.getPublishRun(id) }
    suspend fun resumablePublishRun(): PublishRun? = withContext(Dispatchers.IO) { db.latestResumablePublishRun() }
    suspend fun publishItems(runId: Long): List<PublishItem> = withContext(Dispatchers.IO) { db.getPublishItems(runId) }
    suspend fun pendingPublishItems(runId: Long): List<PublishItem> = withContext(Dispatchers.IO) { db.getPendingPublishItems(runId) }
    suspend fun updatePublishRunStatus(runId: Long, status: PublishRunStatus) = withContext(Dispatchers.IO) { db.updatePublishRunStatus(runId, status) }
    suspend fun updatePublishItem(id: Long, status: PublishStatus, detail: String? = null, incrementAttempt: Boolean = false, verified: Boolean = false) =
        withContext(Dispatchers.IO) { db.updatePublishItem(id, status, detail, incrementAttempt, verified) }
    suspend fun resetPublishTransientItems(runId: Long) = withContext(Dispatchers.IO) { db.resetPublishTransientItems(runId) }
    suspend fun publishStats(runId: Long): PublishStats = withContext(Dispatchers.IO) { db.publishStats(runId) }
    suspend fun clearPublishHistory() = withContext(Dispatchers.IO) { db.clearPublishHistory() }

    suspend fun stats(): ExtractionStats = withContext(Dispatchers.IO) { db.getStats() }
    suspend fun clearAll() = withContext(Dispatchers.IO) { db.clearAll() }
}
