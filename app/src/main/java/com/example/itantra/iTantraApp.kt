package com.example.itantra

import android.app.Application
import com.example.itantra.data.ExperimentEngine
import com.example.itantra.data.ExperimentLogger
import com.example.itantra.metrics.MetricsEngine

class iTantraApp : Application() {

    lateinit var metricsEngine: MetricsEngine
        private set
    lateinit var experimentLogger: ExperimentLogger
        private set

    /** A/B trial engine (Phase 25/26): balanced, seeded block assignment. */
    val experimentEngine: ExperimentEngine = ExperimentEngine()

    override fun onCreate() {
        super.onCreate()
        metricsEngine = MetricsEngine()
        experimentLogger = ExperimentLogger(this)
    }
}
