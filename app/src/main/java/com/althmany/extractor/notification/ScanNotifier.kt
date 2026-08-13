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
import com.althmany.extractor.engine.ScanEngineStatus
import com.althmany.extractor.engine.ScanUiState

class ScanNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        val channel = NotificationChannel(CHANNEL_ID, "فحص روابط واتساب", NotificationManager.IMPORTANCE_LOW).apply {
            description = "تقدم فحص روابط دعوات واتساب"
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(state: ScanUiState, ongoing: Boolean = true) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val openApp = PendingIntent.getActivity(
            context, 30, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val action = if (state.status == ScanEngineStatus.PAUSED) ACTION_RESUME else ACTION_PAUSE
        val actionLabel = if (state.status == ScanEngineStatus.PAUSED) "استكمال" else "إيقاف مؤقت"
        val pauseIntent = PendingIntent.getBroadcast(
            context, 31, Intent(context, ScanActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getBroadcast(
            context, 32, Intent(context, ScanActionReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val max = state.total.coerceAtLeast(1)
        val progress = state.currentIndex.coerceIn(0, max)
        val text = "${state.currentIndex}/${state.total} • مباشر ${state.stats.direct} • موافقة ${state.stats.approval}"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("AL-thmany • Scan Pro")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${state.message}\n$text"))
            .setContentIntent(openApp)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setProgress(max, progress, state.total == 0)
            .addAction(0, actionLabel, pauseIntent)
            .addAction(0, "إنهاء", stopIntent)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun cancel() = manager.cancel(NOTIFICATION_ID)

    companion object {
        const val ACTION_PAUSE = "com.althmany.extractor.SCAN_PAUSE"
        const val ACTION_RESUME = "com.althmany.extractor.SCAN_RESUME"
        const val ACTION_STOP = "com.althmany.extractor.SCAN_STOP"
        private const val CHANNEL_ID = "scan_status"
        private const val NOTIFICATION_ID = 7002
    }
}
