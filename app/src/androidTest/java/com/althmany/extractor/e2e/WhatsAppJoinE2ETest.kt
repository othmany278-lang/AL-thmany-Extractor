package com.althmany.extractor.e2e

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.althmany.extractor.ExtractorApp
import com.althmany.extractor.data.InviteKind
import com.althmany.extractor.data.ScanRecord
import com.althmany.extractor.data.ScanStatus
import com.althmany.extractor.engine.ExtractionController
import com.althmany.extractor.engine.ScanActionMode
import com.althmany.extractor.engine.ScanController
import com.althmany.extractor.engine.ScanEngineStatus
import com.althmany.extractor.engine.ScanScope
import com.althmany.extractor.engine.ScanSpeedProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full emulator E2E for the scanner/joiner.
 *
 * Unlike unit tests, this launches a separate APK with applicationId=com.whatsapp, lets the real
 * AL-thmany AccessibilityService read/click its UI, verifies JOIN_ONLY / SCAN_ONLY semantics, and
 * proves that the controller closes each invite and advances through the whole queue.
 */
@RunWith(AndroidJUnit4::class)
class WhatsAppJoinE2ETest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as ExtractorApp
    private val repo get() = app.repository

    @Before
    fun reset() = runBlocking {
        if (ScanController.isRunning()) ScanController.stop()
        delay(250)
        repo.clearScan()
        ExtractionController.refreshRuntimeEnvironment()
        assertTrue("Fake com.whatsapp APK must be installed and launchable", ExtractionController.setTargetWhatsAppPackage(WHATSAPP))
        ScanController.setSpeed(ScanSpeedProfile.HYPER)
        ScanController.setMaxAttempts(1)
        ScanController.setScope(ScanScope.PENDING_ONLY)
        openSimulatorHome()
        waitForAccessibility()
    }

    @Test
    fun joinOnly_executesActionsAndAdvancesEveryLink() = runBlocking {
        ScanController.setActionMode(ScanActionMode.JOIN_ONLY)
        assertTrue("JOIN_ONLY must force Request-to-Join actions on", ScanController.state.value.requestToJoinEnabled)

        val added = repo.addScanLinksFromText(JOIN_QUEUE)
        assertEquals(8, added)
        ScanController.refreshStats()
        ScanController.start()
        waitForCompletion(70_000)

        val rows = repo.scanItems().associateBy { it.inviteCode }
        assertStatus(rows, "E2EDIRECT001", ScanStatus.JOINED)
        assertStatus(rows, "E2EREQUEST001", ScanStatus.REQUEST_PENDING)
        assertStatus(rows, "E2ECOMMUNITY001", ScanStatus.JOINED)
        assertStatus(rows, "E2EINVALID001", ScanStatus.INVALID)
        assertStatus(rows, "E2EALREADY001", ScanStatus.ALREADY_MEMBER)
        assertStatus(rows, "E2EFULL00001", ScanStatus.FULL)
        assertStatus(rows, "E2EREMOVED01", ScanStatus.REMOVED)
        assertStatus(rows, "E2ELIMIT0001", ScanStatus.ACCOUNT_LIMIT)

        assertEquals(InviteKind.GROUP, rows.getValue("E2EDIRECT001").inviteKind)
        assertEquals(InviteKind.COMMUNITY, rows.getValue("E2ECOMMUNITY001").inviteKind)
        assertEquals("JOIN_VERIFIED", rows.getValue("E2EDIRECT001").signalCode)
        assertEquals("REQUEST_VERIFIED", rows.getValue("E2EREQUEST001").signalCode)
        assertEquals("JOIN_VERIFIED", rows.getValue("E2ECOMMUNITY001").signalCode)
        rows.values.forEach { assertEquals(WHATSAPP, it.targetPackage) }

        Log.i(TAG, "JOIN_ONLY PASS rows=${compact(rows.values)}")
        Unit
    }

    @Test
    fun scanOnly_readsSameScreensWithoutClickingMembershipActions() = runBlocking {
        ScanController.setActionMode(ScanActionMode.SCAN_ONLY)
        val added = repo.addScanLinksFromText(SCAN_QUEUE)
        assertEquals(4, added)
        ScanController.refreshStats()
        ScanController.start()
        waitForCompletion(45_000)

        val rows = repo.scanItems().associateBy { it.inviteCode }
        assertStatus(rows, "E2EDIRECT001", ScanStatus.DIRECT)
        assertStatus(rows, "E2EREQUEST001", ScanStatus.APPROVAL)
        assertStatus(rows, "E2ECOMMUNITY001", ScanStatus.DIRECT)
        assertStatus(rows, "E2EINVALID001", ScanStatus.INVALID)
        assertEquals(InviteKind.COMMUNITY, rows.getValue("E2ECOMMUNITY001").inviteKind)
        assertFalse(rows.values.any { it.status == ScanStatus.JOINED || it.status == ScanStatus.REQUEST_PENDING })

        Log.i(TAG, "SCAN_ONLY PASS rows=${compact(rows.values)}")
        Unit
    }

    private suspend fun waitForAccessibility() {
        withTimeout(25_000) {
            while (!ScanController.state.value.serviceConnected) {
                delay(100)
            }
        }
        Log.i(TAG, "ACCESSIBILITY_CONNECTED target=${ExtractionController.state.value.selectedWhatsAppPackage}")
    }

    private suspend fun waitForCompletion(timeoutMs: Long) {
        var started = false
        withTimeout(timeoutMs) {
            while (true) {
                val state = ScanController.state.value
                if (state.running || state.status in ACTIVE_STATES) started = true
                if (state.status == ScanEngineStatus.ERROR) {
                    throw AssertionError("Scan engine ERROR: ${state.message}")
                }
                if (started && !state.running && state.status == ScanEngineStatus.COMPLETED) break
                delay(80)
            }
        }
        assertEquals(ScanEngineStatus.COMPLETED, ScanController.state.value.status)
    }

    private fun openSimulatorHome() {
        val launch = context.packageManager.getLaunchIntentForPackage(WHATSAPP)
        assertNotNull("Simulator launcher missing", launch)
        context.startActivity(launch!!.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }

    private fun assertStatus(rows: Map<String, ScanRecord>, code: String, expected: ScanStatus) {
        val row = rows[code] ?: throw AssertionError("Missing scan row for $code")
        assertEquals("$code detail=${row.detail} signal=${row.signalCode}", expected, row.status)
    }

    private fun compact(rows: Collection<ScanRecord>): String = rows
        .sortedBy { it.id }
        .joinToString(" | ") { "${it.inviteCode}:${it.status}:${it.signalCode}:${it.durationMs}ms" }

    companion object {
        private const val TAG = "ALThmanyE2E"
        private const val WHATSAPP = "com.whatsapp"
        private val ACTIVE_STATES = setOf(
            ScanEngineStatus.PREPARING,
            ScanEngineStatus.WAITING_NETWORK,
            ScanEngineStatus.OPENING,
            ScanEngineStatus.CLASSIFYING,
            ScanEngineStatus.RETRYING,
            ScanEngineStatus.PAUSED
        )

        private val JOIN_QUEUE = listOf(
            "https://chat.whatsapp.com/E2EDIRECT001",
            "https://chat.whatsapp.com/E2EREQUEST001",
            "https://chat.whatsapp.com/E2ECOMMUNITY001",
            "https://chat.whatsapp.com/E2EINVALID001",
            "https://chat.whatsapp.com/E2EALREADY001",
            "https://chat.whatsapp.com/E2EFULL00001",
            "https://chat.whatsapp.com/E2EREMOVED01",
            "https://chat.whatsapp.com/E2ELIMIT0001"
        ).joinToString("\n")

        private val SCAN_QUEUE = listOf(
            "https://chat.whatsapp.com/E2EDIRECT001",
            "https://chat.whatsapp.com/E2EREQUEST001",
            "https://chat.whatsapp.com/E2ECOMMUNITY001",
            "https://chat.whatsapp.com/E2EINVALID001"
        ).joinToString("\n")
    }
}
