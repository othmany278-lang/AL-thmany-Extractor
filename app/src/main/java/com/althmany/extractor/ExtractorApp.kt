package com.althmany.extractor

import android.app.Application
import com.althmany.extractor.data.ExtractorDatabase
import com.althmany.extractor.data.ExtractorRepository
import com.althmany.extractor.engine.ExtractionController
import com.althmany.extractor.engine.ScanController
import com.althmany.extractor.engine.PublishController

class ExtractorApp : Application() {
    lateinit var repository: ExtractorRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val database = ExtractorDatabase(this)
        repository = ExtractorRepository(database)
        ExtractionController.initialize(this, repository)
        ScanController.initialize(this, repository)
        PublishController.initialize(this, repository)
    }
}
