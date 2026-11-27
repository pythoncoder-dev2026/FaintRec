package com.example.audio

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Lock-free single-producer / single-consumer float ring buffer for zero-drop high-priority
 * audio capture and ultrasonic streaming.
 */
class FloatRingBuffer(val capacitySamples: Int) {

    private val buffer = FloatArray(capacitySamples)
    private val readIndex = AtomicLong(0L)
    private val writeIndex = AtomicLong(0L)
    private val droppedSamplesCounter = AtomicLong(0L)

    val droppedSamples: Long get() = droppedSamplesCounter.get()

    fun reset() {
        readIndex.set(0L)
        writeIndex.set(0L)
        droppedSamplesCounter.set(0L)
    }

    fun availableToRead(): Int {
        val diff = writeIndex.get() - readIndex.get()
        return diff.coerceIn(0L, capacitySamples.toLong()).toInt()
    }

    fun write(src: FloatArray, count: Int): Int {
        if (count <= 0) return 0
        val w = writeIndex.get()
        val r = readIndex.get()
        val free = capacitySamples - (w - r).toInt()
        val toWrite = min(count, free)
        if (toWrite < count) {
            droppedSamplesCounter.addAndGet((count - toWrite).toLong())
        }
        if (toWrite <= 0) return 0

        val startPos = (w % capacitySamples).toInt()
        val firstPart = min(toWrite, capacitySamples - startPos)
        System.arraycopy(src, 0, buffer, startPos, firstPart)
        val secondPart = toWrite - firstPart
        if (secondPart > 0) {
            System.arraycopy(src, firstPart, buffer, 0, secondPart)
        }
        writeIndex.lazySet(w + toWrite)
        return toWrite
    }

    fun read(dst: FloatArray, maxCount: Int): Int {
        if (maxCount <= 0) return 0
        val r = readIndex.get()
        val w = writeIndex.get()
        val avail = (w - r).toInt()
        val toRead = min(maxCount, avail)
        if (toRead <= 0) return 0

        val startPos = (r % capacitySamples).toInt()
        val firstPart = min(toRead, capacitySamples - startPos)
        System.arraycopy(buffer, startPos, dst, 0, firstPart)
        val secondPart = toRead - firstPart
        if (secondPart > 0) {
            System.arraycopy(buffer, 0, dst, firstPart, secondPart)
        }
        readIndex.lazySet(r + toRead)
        return toRead
    }
}

/**
 * Circular overwrite buffer holding the last [capacitySamples] samples (e.g., 2 seconds pre-roll
 * for Voice-Activated Recording) so audio just before the trigger threshold is never lost.
 */
class PreRollBuffer(val capacitySamples: Int) {

    private val data = FloatArray(capacitySamples)
    private var writePos = 0
    private val filled = AtomicInteger(0)

    @Synchronized
    fun clear() {
        writePos = 0
        filled.set(0)
    }

    @Synchronized
    fun push(samples: FloatArray, count: Int) {
        if (count <= 0 || capacitySamples <= 0) return
        var offset = 0
        var remaining = count
        if (remaining >= capacitySamples) {
            offset = count - capacitySamples
            remaining = capacitySamples
            writePos = 0
            filled.set(capacitySamples)
        }
        while (remaining > 0) {
            val space = capacitySamples - writePos
            val chunk = min(remaining, space)
            System.arraycopy(samples, offset, data, writePos, chunk)
            writePos = (writePos + chunk) % capacitySamples
            offset += chunk
            remaining -= chunk
            val curFilled = filled.get()
            if (curFilled < capacitySamples) {
                filled.set(min(capacitySamples, curFilled + chunk))
            }
        }
    }

    @Synchronized
    fun drainChronological(): FloatArray {
        val count = filled.get()
        if (count <= 0) return FloatArray(0)
        val out = FloatArray(count)
        val start = if (count < capacitySamples) 0 else writePos
        val firstPart = min(count, capacitySamples - start)
        System.arraycopy(data, start, out, 0, firstPart)
        val secondPart = count - firstPart
        if (secondPart > 0) {
            System.arraycopy(data, 0, out, firstPart, secondPart)
        }
        clear()
        return out
    }
}
