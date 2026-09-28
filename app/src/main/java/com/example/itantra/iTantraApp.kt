package com.example.itantra

import android.app.Application
import com.example.itantra.data.ExperimentEngine
import com.example.itantra.data.ExperimentLogger
import com.example.itantra.metrics.MetricsEngine
import com.example.itantra.security.SessionKeyManager

class iTantraApp : Application() {

    lateinit var metricsEngine: MetricsEngine
        private set
    lateinit var experimentLogger: ExperimentLogger
        private set

    /**
     * AES-256 session key lifecycle. Created once per process so the paired key
     * is restored from the Android Keystore envelope before any UI can encrypt,
     * and so both ViewModels observe the same instance.
     */
    lateinit var sessionKeyManager: SessionKeyManager
        private set

    /** A/B trial engine (Phase 25/26): balanced, seeded block assignment. */
    val experimentEngine: ExperimentEngine = ExperimentEngine()

    override fun onCreate() {
        super.onCreate()
        metricsEngine = MetricsEngine()
        experimentLogger = ExperimentLogger(this)
        sessionKeyManager = SessionKeyManager(this)
    }
}
