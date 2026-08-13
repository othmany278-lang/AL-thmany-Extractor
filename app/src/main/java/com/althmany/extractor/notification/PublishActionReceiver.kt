package com.althmany.extractor.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.althmany.extractor.engine.PublishController

class PublishActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            PublishNotifier.ACTION_PAUSE -> PublishController.pause()
            PublishNotifier.ACTION_RESUME -> PublishController.resume()
            PublishNotifier.ACTION_STOP -> PublishController.stop()
        }
    }
}
