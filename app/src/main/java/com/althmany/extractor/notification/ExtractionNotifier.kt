package com.althmany.extractor.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.althmany.extractor.MainActivity
import com.althmany.extractor.R
import com.althmany.extractor.data.EngineStatus
import com.althmany.extractor.engine.ExtractionUiState

class ExtractionNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "استخراج الروابط",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "حالة عملية استخراج الروابط من واتساب"
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(state: ExtractionUiState, ongoing: Boolean = true) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val openApp = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseOrResume = if (state.status == EngineStatus.PAUSED) ACTION_RESUME else ACTION_PAUSE
        val pauseLabel = if (state.status == EngineStatus.PAUSED) "استكمال" else "إيقاف مؤقت"
        val pauseIntent = PendingIntent.getBroadcast(
            context,
            11,
            Intent(context, ExtractionActionReceiver::class.java).setAction(pauseOrResume),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getBroadcast(
            context,
            12,
            Intent(context, ExtractionActionReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val progressMax = state.runGroupCount.coerceAtLeast(1)
        val progress = state.currentGroupIndex.coerceIn(0, progressMax)
        val text = buildString {
            state.currentGroup?.let { append(it).append(" • ") }
            append("${state.stats.completedGroups}/${state.stats.totalGroups} عناصر")
            append(" • ${state.stats.totalUniqueLinks} روابط")
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("AL-thmany Extractor")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${state.message}\n$text"))
            .setContentIntent(openApp)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setProgress(progressMax, progress, state.runGroupCount == 0)
            .addAction(0, pauseLabel, pauseIntent)
            .addAction(0, "إنهاء", stopIntent)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)

    companion object {
        const val ACTION_PAUSE = "com.althmany.extractor.PAUSE"
        const val ACTION_RESUME = "com.althmany.extractor.RESUME"
        const val ACTION_STOP = "com.althmany.extractor.STOP"
        private const val CHANNEL_ID = "extraction_status"
        private const val NOTIFICATION_ID = 7001
    }
}
