package com.example.itantra

import android.app.Application
import com.example.itantra.data.ExperimentLogger
import com.example.itantra.metrics.MetricsEngine

class iTantraApp : Application() {

    lateinit var metricsEngine: MetricsEngine
        private set
    lateinit var experimentLogger: ExperimentLogger
        private set

    override fun onCreate() {
        super.onCreate()
        metricsEngine = MetricsEngine()
        experimentLogger = ExperimentLogger(this)
    }
}
