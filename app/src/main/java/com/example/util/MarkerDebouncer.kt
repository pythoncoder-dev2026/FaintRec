package com.example.util

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe monotonic debouncer for user-triggered timestamp markers.
 * Rejects duplicate marker events arriving within [minIntervalMs] (default 500 ms).
 */
class MarkerDebouncer(
    private val minIntervalMs: Long = DEFAULT_INTERVAL_MS
) {
    companion object {
        const val DEFAULT_INTERVAL_MS = 500L
    }

    private val lastAcceptedMs = AtomicLong(Long.MIN_VALUE / 2)

    fun tryAcquire(nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        while (true) {
            val prev = lastAcceptedMs.get()
            if (prev > Long.MIN_VALUE / 4 && (nowMs - prev) < minIntervalMs) {
                return false
            }
            if (lastAcceptedMs.compareAndSet(prev, nowMs)) {
                return true
            }
        }
    }

    fun reset() {
        lastAcceptedMs.set(Long.MIN_VALUE / 2)
    }
}
