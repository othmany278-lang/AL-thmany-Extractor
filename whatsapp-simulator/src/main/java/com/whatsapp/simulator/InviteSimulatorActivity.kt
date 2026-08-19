package com.whatsapp.simulator

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.window.OnBackInvokedDispatcher
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.whatsapp.R

/**
 * Test-only WhatsApp surface used by GitHub Actions.
 *
 * The APK deliberately uses applicationId=com.whatsapp so AL-thmany exercises its real explicit
 * package routing, Accessibility tree reading, semantic button clicks, X/Back recovery and
 * link-to-link queue progression. It contains no WhatsApp code, network access, account or data.
 */
class InviteSimulatorActivity : Activity() {
    private enum class Scenario {
        DIRECT, REQUEST, COMMUNITY, INVALID, ALREADY, FULL, REMOVED, LIMIT, UNKNOWN
    }

    private var activeScenario: Scenario? = null
    private var activeCode: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                Log.i(TAG, "BACK code=$activeCode scenario=$activeScenario")
                activeScenario = null
                activeCode = null
                finish()
            }
        }
        acceptIntent(intent, fresh = true)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        // If the controller opens another invite without first using X/Back, make that navigation
        // bug visible instead of silently accepting the next URL.
        if (activeScenario != null) {
            Log.e(TAG, "NAVIGATION_FAILURE previous=$activeCode next=${intent.data}")
            renderNavigationFailure()
            return
        }
        setIntent(intent)
        acceptIntent(intent, fresh = false)
    }

    @Deprecated("Deprecated in Android, retained here so GLOBAL_ACTION_BACK is observable in CI")
    override fun onBackPressed() {
        Log.i(TAG, "BACK code=$activeCode scenario=$activeScenario")
        activeScenario = null
        activeCode = null
        super.onBackPressed()
    }

    private fun acceptIntent(intent: Intent, fresh: Boolean) {
        val code = intent.data?.lastPathSegment?.trim().orEmpty()
        if (code.isBlank()) {
            activeScenario = null
            activeCode = null
            renderHome()
            Log.i(TAG, "HOME fresh=$fresh")
            return
        }
        activeCode = code
        activeScenario = scenarioFor(code)
        Log.i(TAG, "OPEN code=$code scenario=$activeScenario fresh=$fresh")
        renderScenario(activeScenario ?: Scenario.UNKNOWN)
    }

    private fun scenarioFor(code: String): Scenario = when (code.uppercase()) {
        "E2EDIRECT001" -> Scenario.DIRECT
        "E2EREQUEST001" -> Scenario.REQUEST
        "E2ECOMMUNITY001" -> Scenario.COMMUNITY
        "E2EINVALID001" -> Scenario.INVALID
        "E2EALREADY001" -> Scenario.ALREADY
        "E2EFULL00001" -> Scenario.FULL
        "E2EREMOVED01" -> Scenario.REMOVED
        "E2ELIMIT0001" -> Scenario.LIMIT
        else -> Scenario.UNKNOWN
    }

    private fun renderHome() {
        setContentView(screen("Chats", "Communities", "Updates", "Calls", "WhatsApp E2E Home"))
    }

    private fun renderScenario(scenario: Scenario) {
        when (scenario) {
            Scenario.DIRECT -> renderInvite(
                group = "E2E Direct Group",
                members = "42 members",
                actionText = "Join group",
                actionId = "join_group_button"
            ) {
                Log.i(TAG, "CLICK_JOIN code=$activeCode type=GROUP")
                renderJoinedChat("E2E Direct Group")
            }
            Scenario.REQUEST -> renderInvite(
                group = "E2E Approval Group",
                members = "73 members",
                actionText = "Request to join",
                actionId = "request_to_join_button"
            ) {
                Log.i(TAG, "CLICK_REQUEST code=$activeCode")
                renderRequestPending("E2E Approval Group")
            }
            Scenario.COMMUNITY -> renderInvite(
                group = "E2E Test Community",
                members = "120 members",
                actionText = "Join community",
                actionId = "join_community_button"
            ) {
                Log.i(TAG, "CLICK_JOIN code=$activeCode type=COMMUNITY")
                renderCommunityConfirmation()
            }
            Scenario.INVALID -> renderTerminal("E2E Invalid Invite", "Invite link is invalid")
            Scenario.ALREADY -> renderTerminal("E2E Existing Group", "You're already a member")
            Scenario.FULL -> renderTerminal("E2E Full Group", "This group is full")
            Scenario.REMOVED -> renderTerminal("E2E Removed Group", "You were removed")
            Scenario.LIMIT -> renderTerminal("E2E Limit Group", "You can't join more groups")
            Scenario.UNKNOWN -> renderTerminal("E2E Unknown", "Unable to classify test invite")
        }
    }

    private fun renderInvite(
        group: String,
        members: String,
        actionText: String,
        actionId: String,
        onAction: () -> Unit
    ) {
        val root = baseColumn()
        root.addView(title(group))
        root.addView(label(members))
        addStablePreviewNoise(root)
        root.addView(actionButton(actionText, actionId, onAction))
        root.addView(closeButton())
        setContentView(wrap(root))
    }

    private fun renderCommunityConfirmation() {
        val root = baseColumn()
        root.addView(title("E2E Test Community"))
        root.addView(label("Confirm community membership"))
        addStablePreviewNoise(root)
        root.addView(actionButton("Continue", "continue_button") {
            Log.i(TAG, "CLICK_CONFIRM code=$activeCode")
            renderJoinedChat("E2E Test Community")
        })
        setContentView(wrap(root))
    }

    private fun renderRequestPending(group: String) {
        val root = baseColumn()
        root.addView(title(group))
        root.addView(label("Request pending"))
        root.addView(label("Your request is pending"))
        addStablePreviewNoise(root)
        root.addView(closeButton())
        setContentView(wrap(root))
    }

    private fun renderJoinedChat(group: String) {
        val root = baseColumn()
        root.addView(title(group))
        root.addView(label("Messages"))
        repeat(8) { root.addView(label("E2E message ${it + 1}")) }
        val composer = EditText(this).apply {
            hint = "Message"
            contentDescription = "Message"
            isSingleLine = true
        }
        root.addView(composer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // Intentionally no Close button: the controller must exercise its Back fallback.
        setContentView(wrap(root))
    }

    private fun renderTerminal(group: String, message: String) {
        val root = baseColumn()
        root.addView(title(group))
        root.addView(label(message))
        addStablePreviewNoise(root)
        root.addView(closeButton())
        setContentView(wrap(root))
    }

    private fun renderNavigationFailure() {
        val root = baseColumn()
        root.addView(title("E2E navigation failure"))
        root.addView(label("Previous invite was not closed before the next link"))
        repeat(18) { root.addView(label("navigation failure signal ${it + 1}")) }
        setContentView(wrap(root))
    }

    private fun closeButton(): Button = actionButton("Close", "close_button") {
        Log.i(TAG, "CLOSE code=$activeCode scenario=$activeScenario")
        activeScenario = null
        activeCode = null
        finish()
    }

    private fun actionButton(text: String, idName: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        contentDescription = text
        id = when (idName) {
            "join_group_button" -> R.id.join_group_button
            "request_to_join_button" -> R.id.request_to_join_button
            "join_community_button" -> R.id.join_community_button
            "continue_button" -> R.id.continue_button
            "close_button" -> R.id.close_button
            else -> View.generateViewId()
        }
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun addStablePreviewNoise(root: LinearLayout) {
        // The production scanner intentionally waits for a stable, non-blank Accessibility tree.
        // These nodes mimic the surrounding WhatsApp preview chrome so the real stability gate runs.
        repeat(18) { root.addView(label("preview signal ${it + 1}")) }
        root.addView(label("Chats"))
        root.addView(label("Communities"))
    }

    private fun baseColumn(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(32, 48, 32, 48)
        setBackgroundColor(Color.WHITE)
    }

    private fun wrap(content: LinearLayout): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun screen(vararg labels: String): ScrollView {
        val root = baseColumn()
        labels.forEach { root.addView(label(it)) }
        repeat(18) { root.addView(label("home signal ${it + 1}")) }
        return wrap(root)
    }

    private fun title(value: String): TextView = label(value).apply {
        textSize = 22f
        contentDescription = value
    }

    private fun label(value: String): TextView = TextView(this).apply {
        text = value
        contentDescription = value
        textSize = 16f
        setTextColor(Color.BLACK)
        setPadding(8, 8, 8, 8)
    }

    companion object {
        private const val TAG = "WhatsAppSim"
    }
}
