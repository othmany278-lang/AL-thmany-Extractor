package com.althmany.extractor.data

import com.althmany.extractor.engine.InviteLinkParser
import com.althmany.extractor.engine.ScanScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExtractorRepository(private val db: ExtractorDatabase) {
    suspend fun addGroupsFromText(text: String, whatsappPackage: String = "") = withContext(Dispatchers.IO) {
        db.replaceGroups(text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList(), whatsappPackage)
    }
    suspend fun addDiscoveredGroups(names: Collection<String>): Int = withContext(Dispatchers.IO) { db.addDiscoveredGroups(names) }
    suspend fun addDiscoveredGroupCandidates(candidates: Collection<GroupSyncCandidate>): Int = withContext(Dispatchers.IO) {
        db.addDiscoveredGroupCandidates(candidates)
    }
    suspend fun groups(): List<TargetGroup> = withContext(Dispatchers.IO) { db.getGroups() }
    suspend fun groupByName(name: String, whatsappPackage: String? = null): TargetGroup? =
        withContext(Dispatchers.IO) { db.getGroupByName(name, whatsappPackage) }
    suspend fun pendingSelectedGroups(whatsappPackage: String? = null): List<TargetGroup> = withContext(Dispatchers.IO) { db.getSelectedPendingGroups(whatsappPackage) }
    suspend fun selectedGroups(): List<TargetGroup> = withContext(Dispatchers.IO) { db.getSelectedGroups() }
    suspend fun setSelected(id: Long, selected: Boolean) = withContext(Dispatchers.IO) { db.setGroupSelected(id, selected) }
    suspend fun setAllSelected(selected: Boolean) = withContext(Dispatchers.IO) { db.setAllGroupsSelected(selected) }
    suspend fun setSelectionPreset(preset: GroupSelectionPreset, whatsappPackage: String? = null) = withContext(Dispatchers.IO) { db.setSelectionPreset(preset, whatsappPackage) }
    suspend fun updateStatus(id: Long, status: GroupStatus, error: String? = null) = withContext(Dispatchers.IO) { db.updateGroupStatus(id, status, error) }
    suspend fun markVerifiedGroup(id: Long, verified: Boolean) = withContext(Dispatchers.IO) { db.markVerifiedGroup(id, verified) }
    suspend fun updateGroupCapabilities(id: Long, verified: Boolean, active: Boolean, publishable: Boolean, communityParent: Boolean = false) =
        withContext(Dispatchers.IO) { db.updateGroupCapabilities(id, verified, active, publishable, communityParent) }
    suspend fun recordGroupAccessSuccess(id: Long, method: GroupAccessMethod) =
        withContext(Dispatchers.IO) { db.updateGroupAccessSuccess(id, method) }
    suspend fun recordGroupAccessFailure(id: Long, method: GroupAccessMethod) =
        withContext(Dispatchers.IO) { db.updateGroupAccessFailure(id, method) }
    suspend fun updateGroupIdentity(id: Long, jidOrGroupId: String?, whatsappPackage: String) =
        withContext(Dispatchers.IO) { db.updateGroupIdentity(id, jidOrGroupId, whatsappPackage) }
    suspend fun updateGroupPublishState(id: Long, status: PublishStatus, error: String? = null) =
        withContext(Dispatchers.IO) { db.updateGroupPublishState(id, status, error) }
    suspend fun resetRunStatuses(whatsappPackage: String? = null) = withContext(Dispatchers.IO) { db.resetRunStatuses(whatsappPackage) }
    suspend fun saveLink(url: String, normalized: String, groupName: String): Boolean = withContext(Dispatchers.IO) {
        db.upsertLink(url, normalized, groupName, System.currentTimeMillis())
    }
    suspend fun saveLinksBatch(links: List<LinkCandidate>, group: TargetGroup): Int = withContext(Dispatchers.IO) {
        db.upsertLinksBatch(links, group, System.currentTimeMillis())
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
    suspend fun createPublishRun(
        message: String,
        targetPackage: String,
        delayMs: Long,
        maxAttempts: Int,
        groupNames: List<String>,
        contentMode: PublishContentMode,
        attachmentUri: String?,
        attachmentMime: String?,
        runToken: String
    ): Long = withContext(Dispatchers.IO) {
        db.createPublishRun(message, targetPackage, delayMs, maxAttempts, groupNames, contentMode, attachmentUri, attachmentMime, runToken)
    }
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
