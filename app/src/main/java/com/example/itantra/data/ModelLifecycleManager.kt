package com.example.itantra.data

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Explicit operational states for an edge machine learning model in RAM.
 */
enum class ModelLifecycleState {
    UNLOADED,
    LOADING,
    READY,
    ACTIVE,
    EVICTING,
    ERROR
}

/**
 * Manages the memory residency and lifecycle of offline speech models.
 *
 * Enforces strict 4 GB RAM resource constraints:
 * - Single-active-model policy: ASR and TTS runtimes never concurrently occupy RAM.
 * - Active inference guard: Models cannot be evicted while active inference is in flight.
 * - Explicit transition tracking: UNLOADED -> LOADING -> READY -> ACTIVE -> EVICTING.
 */
class ModelLifecycleManager {

    companion object {
        private const val TAG = "ModelLifecycleManager"
    }

    private val lock = Any()

    private var activeSttModel: ModelManifest? = null
    private var activeTtsModel: ModelManifest? = null

    private var sttState: ModelLifecycleState = ModelLifecycleState.UNLOADED
    private var ttsState: ModelLifecycleState = ModelLifecycleState.UNLOADED

    private val sttInferenceCount = AtomicInteger(0)
    private val ttsInferenceCount = AtomicInteger(0)

    val currentSttState: ModelLifecycleState get() = synchronized(lock) { sttState }
    val currentTtsState: ModelLifecycleState get() = synchronized(lock) { ttsState }
    val currentSttModel: ModelManifest? get() = synchronized(lock) { activeSttModel }
    val currentTtsModel: ModelManifest? get() = synchronized(lock) { activeTtsModel }

    private fun safeLogWarn(msg: String) {
        try {
            Log.w(TAG, msg)
        } catch (_: Throwable) {
            // Android Log is not mocked in local JVM tests
        }
    }

    /**
     * Prepares to load or activate an STT model.
     * Enforces the single-active-model rule: if a TTS model is resident, it triggers TTS eviction.
     */
    fun acquireStt(manifest: ModelManifest): Boolean = synchronized(lock) {
        if (ttsInferenceCount.get() > 0) {
            // Cannot evict TTS while speech synthesis is actively rendering audio
            safeLogWarn("Cannot acquire STT: TTS is currently active in synthesis")
            return false
        }

        // Evict resident TTS model to free up RAM before ASR allocation
        if (activeTtsModel != null && ttsState != ModelLifecycleState.UNLOADED) {
            evictTtsInternal()
        }

        sttState = ModelLifecycleState.LOADING
        activeSttModel = manifest
        sttState = ModelLifecycleState.READY
        return true
    }

    /**
     * Marks start of STT inference operation.
     */
    fun startSttInference(): Boolean = synchronized(lock) {
        if (sttState != ModelLifecycleState.READY && sttState != ModelLifecycleState.ACTIVE) {
            return false
        }
        sttInferenceCount.incrementAndGet()
        sttState = ModelLifecycleState.ACTIVE
        return true
    }

    /**
     * Marks end of STT inference operation.
     */
    fun finishSttInference() = synchronized(lock) {
        val remaining = sttInferenceCount.decrementAndGet()
        if (remaining <= 0) {
            sttInferenceCount.set(0)
            if (sttState == ModelLifecycleState.ACTIVE) {
                sttState = ModelLifecycleState.READY
            }
        }
    }

    /**
     * Prepares to load or activate a TTS voice model.
     * Enforces single-active-model rule: evicts resident ASR model.
     */
    fun acquireTts(manifest: ModelManifest): Boolean = synchronized(lock) {
        if (sttInferenceCount.get() > 0) {
            safeLogWarn("Cannot acquire TTS: STT is currently capturing speech")
            return false
        }

        // Evict resident ASR model to release RAM
        if (activeSttModel != null && sttState != ModelLifecycleState.UNLOADED) {
            evictSttInternal()
        }

        ttsState = ModelLifecycleState.LOADING
        activeTtsModel = manifest
        ttsState = ModelLifecycleState.READY
        return true
    }

    fun startTtsInference(): Boolean = synchronized(lock) {
        if (ttsState != ModelLifecycleState.READY && ttsState != ModelLifecycleState.ACTIVE) {
            return false
        }
        ttsInferenceCount.incrementAndGet()
        ttsState = ModelLifecycleState.ACTIVE
        return true
    }

    fun finishTtsInference() = synchronized(lock) {
        val remaining = ttsInferenceCount.decrementAndGet()
        if (remaining <= 0) {
            ttsInferenceCount.set(0)
            if (ttsState == ModelLifecycleState.ACTIVE) {
                ttsState = ModelLifecycleState.READY
            }
        }
    }

    fun evictStt(): Boolean = synchronized(lock) {
        if (sttInferenceCount.get() > 0) return false
        evictSttInternal()
        return true
    }

    fun evictTts(): Boolean = synchronized(lock) {
        if (ttsInferenceCount.get() > 0) return false
        evictTtsInternal()
        return true
    }

    private fun evictSttInternal() {
        sttState = ModelLifecycleState.EVICTING
        activeSttModel = null
        sttInferenceCount.set(0)
        sttState = ModelLifecycleState.UNLOADED
    }

    private fun evictTtsInternal() {
        ttsState = ModelLifecycleState.EVICTING
        activeTtsModel = null
        ttsInferenceCount.set(0)
        ttsState = ModelLifecycleState.UNLOADED
    }

    fun reset() = synchronized(lock) {
        sttInferenceCount.set(0)
        ttsInferenceCount.set(0)
        activeSttModel = null
        activeTtsModel = null
        sttState = ModelLifecycleState.UNLOADED
        ttsState = ModelLifecycleState.UNLOADED
    }
}
