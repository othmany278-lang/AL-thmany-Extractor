package com.althmany.extractor.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.althmany.extractor.engine.ExtractionController

class ExtractionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            ExtractionNotifier.ACTION_PAUSE -> ExtractionController.pause()
            ExtractionNotifier.ACTION_RESUME -> ExtractionController.resume()
            ExtractionNotifier.ACTION_STOP -> ExtractionController.stop()
        }
    }
}
