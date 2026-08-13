package com.althmany.extractor.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.althmany.extractor.engine.ScanController

class ScanActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            ScanNotifier.ACTION_PAUSE -> ScanController.pause()
            ScanNotifier.ACTION_RESUME -> ScanController.resume()
            ScanNotifier.ACTION_STOP -> ScanController.stop()
        }
    }
}
