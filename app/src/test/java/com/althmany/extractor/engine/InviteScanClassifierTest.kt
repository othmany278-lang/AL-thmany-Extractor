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
        val result = InviteScanClassifier.classify(listOf("مجتمع الوظائف", "245 مشاركًا", "طلب الانضمام", "المجتمع"))
        assertEquals(InviteKind.COMMUNITY, result.inviteKind)
        assertTrue(result.memberCountText?.contains("245") == true)
        assertTrue(result.confidence >= 90)
    }
}
