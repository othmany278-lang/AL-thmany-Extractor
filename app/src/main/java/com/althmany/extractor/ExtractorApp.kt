package com.althmany.extractor

import android.app.Application
import com.althmany.extractor.data.ExtractorDatabase
import com.althmany.extractor.data.ExtractorRepository
import com.althmany.extractor.diagnostics.DiagnosticLog
import com.althmany.extractor.engine.ExtractionController
import com.althmany.extractor.engine.PublishController
import com.althmany.extractor.engine.ScanController

class ExtractorApp : Application() {
    lateinit var repository: ExtractorRepository
        private set

    override fun onCreate() {
        super.onCreate()

        DiagnosticLog.initialize(this)
        DiagnosticLog.record("APP_INIT", "application_onCreate")

        DiagnosticLog.record("APP_INIT", "database_initialize")
        val database = ExtractorDatabase(this)
        repository = ExtractorRepository(database)

        DiagnosticLog.record("APP_INIT", "extraction_controller_initialize")
        ExtractionController.initialize(this, repository)

        DiagnosticLog.record("APP_INIT", "scan_controller_initialize")
        ScanController.initialize(this, repository)

        DiagnosticLog.record("APP_INIT", "publish_controller_initialize")
        PublishController.initialize(this, repository)

        DiagnosticLog.startRuntimeSampler()
        DiagnosticLog.record("APP_INIT", "application_ready")
    }
}
