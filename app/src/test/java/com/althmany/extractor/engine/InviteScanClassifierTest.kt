package com.althmany.extractor.engine

import com.althmany.extractor.data.InviteKind
import com.althmany.extractor.data.ScanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InviteScanClassifierTest {
    @Test fun classifiesCoreInviteStates() {
        assertEquals(ScanStatus.DIRECT, InviteScanClassifier.classify(listOf("الانضمام إلى المجموعة")).status)
        assertEquals(ScanStatus.APPROVAL, InviteScanClassifier.classify(listOf("طلب الانضمام")).status)
        assertEquals(ScanStatus.INVALID, InviteScanClassifier.classify(listOf("رابط الدعوة غير صالح")).status)
        assertEquals(ScanStatus.FULL, InviteScanClassifier.classify(listOf("المجموعة ممتلئة")).status)
    }

    @Test fun extractsCommunityMetadataWhenVisible() {
        val result = InviteScanClassifier.classify(listOf("مجتمع الوظائف", "245 مشاركًا", "طلب الانضمام إلى المجتمع", "المجتمعات"))
        assertEquals(InviteKind.COMMUNITY, result.inviteKind)
        assertTrue(result.memberCountText?.contains("245") == true)
        assertTrue(result.confidence >= 90)
    }

    @Test fun communitiesBottomNavDoesNotTurnGroupInviteIntoCommunity() {
        val result = InviteScanClassifier.classify(listOf("المجتمعات", "Communities", "مجموعة الطب", "الانضمام إلى المجموعة"))
        assertEquals(ScanStatus.DIRECT, result.status)
        assertEquals(InviteKind.GROUP, result.inviteKind)
        assertEquals("مجموعة الطب", result.groupName)
    }

    @Test fun explicitCommunityJoinWinsOverGenericNavigationText() {
        val result = InviteScanClassifier.classify(listOf("Chats", "Communities", "وظائف اليمن", "Join this community now"))
        assertEquals(ScanStatus.DIRECT, result.status)
        assertEquals(InviteKind.COMMUNITY, result.inviteKind)
        assertEquals("وظائف اليمن", result.groupName)
    }
}
