package com.althmany.extractor.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.althmany.extractor.BuildConfig
import com.althmany.extractor.data.ExtractionLog
import com.althmany.extractor.data.PublishItem
import com.althmany.extractor.data.ScanRecord
import com.althmany.extractor.engine.ExtractionController
import com.althmany.extractor.engine.PublishController
import com.althmany.extractor.engine.ScanController
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Persistent real-device diagnostic recorder.
 *
 * Goals:
 *  - survive application restart;
 *  - capture Accessibility binding/events;
 *  - capture runtime state transitions without slowing the fast join loop;
 *  - capture uncaught crashes and suspected main-thread stalls;
 *  - create one privacy-aware TXT report that can be shared from the app.
 *
 * It intentionally does NOT dump WhatsApp message bodies.
 */
object DiagnosticLog {
    private const val MAX_LOG_CHARS = 700_000
    private const val KEEP_LOG_CHARS = 420_000
    private const val ACCESS_EVENT_THROTTLE_MS = 600L
    private const val MAIN_THREAD_STALL_MS = 5_500L

    private lateinit var appContext: Context
    private val initialized = AtomicBoolean(false)
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "althmany-diagnostic-writer").apply { isDaemon = true }
    }
    private val fileLock = Any()

    private val lastMainBeat = AtomicLong(0L)
    private val stallReported = AtomicBoolean(false)
    private val samplerStarted = AtomicBoolean(false)

    @Volatile private var lastAccessKey = ""
    @Volatile private var lastAccessAt = 0L

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val reportFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    private val inviteRegex =
        Regex("""https://chat\.whatsapp\.com/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)

    private val waMeRegex =
        Regex("""https?://wa\.me/(\d+)""", RegexOption.IGNORE_CASE)

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return

        appContext = context.applicationContext

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val message = buildString {
                    append("thread=")
                    append(thread.name)
                    append(" | ")
                    append(throwable::class.java.name)
                    append(": ")
                    append(throwable.message.orEmpty())
                    append('\n')
                    append(throwable.stackTraceToString())
                }
                appendSync("CRASH", message)
                crashFile().parentFile?.mkdirs()
                crashFile().writeText(redact(message))
            }

            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }

        record(
            "APP_START",
            "package=${appContext.packageName} " +
                "version=${BuildConfig.VERSION_NAME} build=${BuildConfig.BUILD_TYPE} " +
                "device=${Build.MANUFACTURER}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT}"
        )

        if (crashFile().exists()) {
            record("PREVIOUS_CRASH", "last_crash.txt موجود من تشغيل سابق")
        }

        startMainThreadWatchdog()
    }

    /**
     * Samples public controller state only.
     * No accessibility tree and no message content are copied.
     */
    fun startRuntimeSampler() {
        if (!samplerStarted.compareAndSet(false, true)) return

        thread(
            start = true,
            isDaemon = true,
            name = "althmany-runtime-sampler"
        ) {
            var lastSummary = ""

            while (true) {
                runCatching {
                    val engine = ExtractionController.state.value
                    val scan = ScanController.state.value
                    val publish = PublishController.state.value

                    val summary = buildString {
                        append("ENGINE=")
                        append(engine.status)
                        append(" target=")
                        append(engine.selectedWhatsAppPackage ?: "none")
                        append(" access=")
                        append(engine.serviceConnected)
                        append(" group=")
                        append(engine.currentGroupIndex)
                        append("/")
                        append(engine.runGroupCount)
                        append(" msg=")
                        append(engine.message.take(160))

                        append(" | SCAN=")
                        append(scan.status)
                        append(" running=")
                        append(scan.running)
                        append(" access=")
                        append(scan.serviceConnected)
                        append(" index=")
                        append(scan.currentIndex)
                        append("/")
                        append(scan.total)
                        append(" attempt=")
                        append(scan.currentAttempt)
                        append(" url=")
                        append(scan.currentUrl.orEmpty())
                        append(" msg=")
                        append(scan.message.take(180))

                        append(" | PUBLISH=")
                        append(publish.status)
                        append(" running=")
                        append(publish.running)
                        append(" index=")
                        append(publish.currentIndex)
                        append("/")
                        append(publish.total)
                        append(" group=")
                        append(publish.currentGroup.orEmpty())
                        append(" info=")
                        append(publish.info.take(180))
                    }

                    if (summary != lastSummary) {
                        lastSummary = summary
                        record("STATE", summary)
                    }
                }

                Thread.sleep(650L)
            }
        }
    }

    fun record(category: String, message: String, throwable: Throwable? = null) {
        if (!initialized.get()) return

        val full = if (throwable == null) {
            message
        } else {
            "$message | ${throwable::class.java.name}: ${throwable.message.orEmpty()}\n" +
                throwable.stackTraceToString()
        }

        writer.execute {
            runCatching { appendSync(category, full) }
        }
    }

    fun recordAccessibilityEvent(packageName: String?, eventType: Int) {
        val pkg = packageName.orEmpty()
        val now = SystemClock.uptimeMillis()
        val key = "$pkg:$eventType"

        if (key == lastAccessKey && now - lastAccessAt < ACCESS_EVENT_THROTTLE_MS) {
            return
        }

        lastAccessKey = key
        lastAccessAt = now

        record(
            "ACCESS_EVENT",
            "package=${pkg.ifBlank { "null" }} eventType=$eventType"
        )
    }

    fun shareReport(
        context: Context,
        runtimeLines: List<String>,
        dbLogs: List<ExtractionLog>,
        scanItems: List<ScanRecord>,
        publishItems: List<PublishItem>
    ) {
        runCatching {
            val report = buildReport(
                context = context,
                runtimeLines = runtimeLines,
                dbLogs = dbLogs,
                scanItems = scanItems,
                publishItems = publishItems
            )

            val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
            val file = File(
                directory,
                "AL-thmany-diagnostic-${reportFormat.format(Date())}.txt"
            )
            file.writeText(report)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.diagnostics.fileprovider",
                file
            )

            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "AL-thmany Diagnostic Log")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "سجل تشخيص AL-thmany مرفق. لا يتم تضمين محتوى رسائل واتساب."
                )
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(send, "مشاركة سجل AL-thmany").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            record("SHARE", "diagnostic_report_created file=${file.name}")
            context.startActivity(chooser)
        }.onFailure {
            record("SHARE_ERROR", "تعذر إنشاء/مشاركة السجل", it)
            Toast.makeText(
                context,
                "تعذر مشاركة السجل: ${it.message.orEmpty()}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun copyReport(
        context: Context,
        runtimeLines: List<String>,
        dbLogs: List<ExtractionLog>,
        scanItems: List<ScanRecord>,
        publishItems: List<PublishItem>
    ) {
        runCatching {
            val report = buildReport(
                context,
                runtimeLines,
                dbLogs,
                scanItems,
                publishItems
            )

            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            clipboard.setPrimaryClip(
                ClipData.newPlainText("AL-thmany diagnostic", report)
            )

            record("SHARE", "diagnostic_report_copied")
            Toast.makeText(context, "تم نسخ سجل التشخيص", Toast.LENGTH_SHORT).show()
        }.onFailure {
            record("COPY_ERROR", "تعذر نسخ سجل التشخيص", it)
            Toast.makeText(
                context,
                "تعذر نسخ السجل",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun buildReport(
        context: Context,
        runtimeLines: List<String>,
        dbLogs: List<ExtractionLog>,
        scanItems: List<ScanRecord>,
        publishItems: List<PublishItem>
    ): String {
        flushPendingWrites()

        val enabledServices = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
        }.getOrDefault("")

        val expectedService =
            "${context.packageName}/com.althmany.extractor.accessibility.WhatsAppAccessibilityService"

        val accessibilityEnabled =
            enabledServices.split(':').any {
                it.equals(expectedService, ignoreCase = true)
            } || enabledServices.contains(
                "com.althmany.extractor.accessibility.WhatsAppAccessibilityService",
                ignoreCase = true
            )

        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()

        val recentRuntime = runCatching {
            runtimeFile()
                .takeIf { it.exists() }
                ?.readLines()
                ?.takeLast(320)
                ?.joinToString("\n")
                .orEmpty()
        }.getOrDefault("")

        val previousCrash = runCatching {
            crashFile().takeIf { it.exists() }?.readText().orEmpty()
        }.getOrDefault("")

        return buildString {
            appendLine("AL-thmany REAL DEVICE DIAGNOSTIC")
            appendLine("================================")
            appendLine("Generated: ${timeFormat.format(Date())}")
            appendLine("Package: ${context.packageName}")
            appendLine("Version: ${packageInfo?.versionName ?: BuildConfig.VERSION_NAME}")
            appendLine("Build type: ${BuildConfig.BUILD_TYPE}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
            appendLine("Accessibility setting detected: $accessibilityEnabled")
            appendLine("Accessibility component: $expectedService")
            appendLine()

            appendLine("PRIVACY")
            appendLine("-------")
            appendLine("WhatsApp message bodies are not intentionally collected.")
            appendLine("Invite codes are masked before export.")
            appendLine()

            appendLine("CURRENT RUNTIME SNAPSHOT")
            appendLine("------------------------")
            runtimeLines.forEach { appendLine(redact(it)) }
            appendLine()

            appendLine("RECENT LIVE DIAGNOSTIC EVENTS")
            appendLine("-----------------------------")
            if (recentRuntime.isBlank()) {
                appendLine("No runtime diagnostic events.")
            } else {
                appendLine(redact(recentRuntime))
            }
            appendLine()

            appendLine("APPLICATION / EXTRACTION LOGS")
            appendLine("-----------------------------")
            val orderedLogs = dbLogs
                .sortedByDescending { it.timestamp }
                .take(250)

            if (orderedLogs.isEmpty()) {
                appendLine("No database logs.")
            } else {
                orderedLogs.forEach { log ->
                    appendLine(
                        redact(
                            "${timeFormat.format(Date(log.timestamp))} " +
                                "[${log.level}] ${log.code} " +
                                "group=${log.groupName.orEmpty()} | ${log.message}"
                        )
                    )
                }
            }
            appendLine()

            appendLine("SCAN / JOIN RESULTS")
            appendLine("-------------------")
            val orderedScan = scanItems
                .sortedByDescending { it.scannedAt ?: it.addedAt }
                .take(160)

            if (orderedScan.isEmpty()) {
                appendLine("No scan items.")
            } else {
                orderedScan.forEach { item ->
                    appendLine(
                        redact(
                            "id=${item.id} status=${item.status} " +
                                "kind=${item.inviteKind} attempts=${item.attempts} " +
                                "confidence=${item.confidence} " +
                                "signal=${item.signalCode.orEmpty()} " +
                                "durationMs=${item.durationMs ?: -1} " +
                                "package=${item.targetPackage.orEmpty()} " +
                                "group=${item.groupName.orEmpty()} " +
                                "url=${item.normalizedUrl} " +
                                "detail=${item.detail.orEmpty()}"
                        )
                    )
                }
            }
            appendLine()

            appendLine("PUBLISH RESULTS")
            appendLine("---------------")
            val orderedPublish = publishItems
                .sortedByDescending { it.sentAt ?: it.id }
                .take(160)

            if (orderedPublish.isEmpty()) {
                appendLine("No publish items.")
            } else {
                orderedPublish.forEach { item ->
                    appendLine(
                        redact(
                            "id=${item.id} run=${item.runId} " +
                                "group=${item.groupName} status=${item.status} " +
                                "attempts=${item.attempts} verified=${item.verified} " +
                                "detail=${item.detail.orEmpty()}"
                        )
                    )
                }
            }

            if (previousCrash.isNotBlank()) {
                appendLine()
                appendLine("LAST UNCAUGHT CRASH")
                appendLine("-------------------")
                appendLine(redact(previousCrash))
            }
        }
    }

    private fun startMainThreadWatchdog() {
        val handler = Handler(Looper.getMainLooper())
        lastMainBeat.set(SystemClock.uptimeMillis())

        thread(
            start = true,
            isDaemon = true,
            name = "althmany-main-watchdog"
        ) {
            while (true) {
                handler.post {
                    lastMainBeat.set(SystemClock.uptimeMillis())
                    stallReported.set(false)
                }

                Thread.sleep(1_500L)

                val lag =
                    SystemClock.uptimeMillis() - lastMainBeat.get()

                if (
                    lag >= MAIN_THREAD_STALL_MS &&
                    stallReported.compareAndSet(false, true)
                ) {
                    appendSync(
                        "ANR_WATCHDOG",
                        "main_thread_unresponsive_ms=$lag"
                    )
                }
            }
        }
    }

    private fun appendSync(category: String, message: String) {
        if (!initialized.get()) return

        synchronized(fileLock) {
            val file = runtimeFile()
            file.parentFile?.mkdirs()

            file.appendText(
                "${timeFormat.format(Date())} [$category] ${redact(message)}\n"
            )

            if (file.length() > MAX_LOG_CHARS) {
                val tail =
                    file.readText().takeLast(KEEP_LOG_CHARS)
                file.writeText(
                    "---- LOG ROTATED ----\n$tail"
                )
            }
        }
    }

    private fun flushPendingWrites() {
        runCatching {
            writer.submit {}.get(800L, TimeUnit.MILLISECONDS)
        }
    }

    private fun runtimeFile(): File =
        File(appContext.filesDir, "diagnostics/runtime.log")

    private fun crashFile(): File =
        File(appContext.filesDir, "diagnostics/last_crash.txt")

    private fun redact(input: String): String {
        var text = input

        text = text.replace(inviteRegex) { match ->
            val code = match.groupValues[1]
            val suffix = code.takeLast(4)
            "https://chat.whatsapp.com/********$suffix"
        }

        text = text.replace(waMeRegex) { match ->
            val number = match.groupValues[1]
            val suffix = number.takeLast(3)
            "https://wa.me/********$suffix"
        }

        return text
    }
}
