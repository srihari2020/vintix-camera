package com.srihari.vintix.telemetry

import com.srihari.vintix.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

object PerformanceTelemetry {
    private val _fps = MutableStateFlow(0)
    val fps: StateFlow<Int> = _fps.asStateFlow()

    private val _glRenderTimeMs = MutableStateFlow(0f)
    val glRenderTimeMs: StateFlow<Float> = _glRenderTimeMs.asStateFlow()

    private val _exportTimeMs = MutableStateFlow(0L)
    val exportTimeMs: StateFlow<Long> = _exportTimeMs.asStateFlow()

    private val _processingTimeMs = MutableStateFlow(0L)
    val processingTimeMs: StateFlow<Long> = _processingTimeMs.asStateFlow()

    private val _memoryUsageMb = MutableStateFlow(0L)
    val memoryUsageMb: StateFlow<Long> = _memoryUsageMb.asStateFlow()

    // FPS tracking
    private var frameCount = 0
    private var lastFpsTimeNs = 0L

    fun recordFrame(durationNs: Long) {
        if (!BuildConfig.TELEMETRY_ENABLED) return
        
        val nowNs = System.nanoTime()
        if (lastFpsTimeNs == 0L) lastFpsTimeNs = nowNs
        
        frameCount++
        
        // Update FPS once per second
        if (nowNs - lastFpsTimeNs >= TimeUnit.SECONDS.toNanos(1)) {
            _fps.value = frameCount
            frameCount = 0
            lastFpsTimeNs = nowNs
        }

        // Convert duration to MS
        _glRenderTimeMs.value = durationNs / 1_000_000f
    }

    fun recordExport(durationNs: Long) {
        if (!BuildConfig.TELEMETRY_ENABLED) return
        _exportTimeMs.value = TimeUnit.NANOSECONDS.toMillis(durationNs)
    }

    fun recordProcessing(durationNs: Long) {
        if (!BuildConfig.TELEMETRY_ENABLED) return
        _processingTimeMs.value = TimeUnit.NANOSECONDS.toMillis(durationNs)
    }

    fun updateMemory() {
        if (!BuildConfig.TELEMETRY_ENABLED) return
        val runtime = Runtime.getRuntime()
        val usedMemBytes = runtime.totalMemory() - runtime.freeMemory()
        _memoryUsageMb.value = usedMemBytes / (1024 * 1024)
    }
}
