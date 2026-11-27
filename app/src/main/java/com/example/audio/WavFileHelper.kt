package com.example.audio

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val isFloat: Boolean,
    val dataOffset: Long,
    val dataSize: Long
) {
    val bytesPerSample: Int get() = bitsPerSample / 8
    val frameSizeBytes: Int get() = bytesPerSample * channels
    val totalFrames: Long get() = if (frameSizeBytes > 0) dataSize / frameSizeBytes else 0L
    val durationMs: Long get() = if (sampleRate > 0) (totalFrames * 1000L) / sampleRate else 0L
}

data class WavCuePoint(
    val id: Int,
    val sampleOffset: Long,
    val label: String
)

object WavFileHelper {

    const val FORMAT_PCM = 1
    const val FORMAT_IEEE_FLOAT = 3

    /**
     * Writes an initial 44-byte RIFF/WAVE header (PCM 16-bit or IEEE 32-bit Float)
     * with placeholder sizes that are patched once recording/export completes.
     */
    fun writePlaceholderHeader(
        raf: RandomAccessFile,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        isFloat: Boolean
    ) {
        raf.seek(0L)
        val audioFormat = if (isFloat) FORMAT_IEEE_FLOAT else FORMAT_PCM
        val blockAlign = channels * (bitsPerSample / 8)
        val byteRate = sampleRate * blockAlign

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36) // Placeholder ChunkSize
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // Subchunk1Size
        header.putShort(audioFormat.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(0) // Placeholder Subchunk2Size
        raf.write(header.array())
    }

    /**
     * Patches RIFF and data chunk sizes at offsets 4 and 40.
     */
    fun patchWavHeaderSizes(raf: RandomAccessFile, dataByteCount: Long) {
        val clampedData = min(dataByteCount, 0xFFFF_FF00L)
        val riffSize = clampedData + 36L
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)

        raf.seek(4L)
        buf.clear()
        buf.putInt(riffSize.toInt())
        raf.write(buf.array())

        raf.seek(40L)
        buf.clear()
        buf.putInt(clampedData.toInt())
        raf.write(buf.array())
    }

    /**
     * Builds a standard 44-byte RIFF/WAVE header byte array.
     */
    fun buildHeaderBytes(
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        isFloat: Boolean,
        dataByteCount: Long = 0L
    ): ByteArray {
        val audioFormat = if (isFloat) FORMAT_IEEE_FLOAT else FORMAT_PCM
        val blockAlign = channels * (bitsPerSample / 8)
        val byteRate = sampleRate * blockAlign
        val clampedData = min(dataByteCount.coerceAtLeast(0L), 0xFFFF_FF00L)
        val riffSize = clampedData + 36L

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(riffSize.toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(audioFormat.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(clampedData.toInt())
        return header.array()
    }

    fun writePlaceholderHeader(
        channel: FileChannel,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        isFloat: Boolean
    ) {
        channel.position(0L)
        val buf = ByteBuffer.wrap(buildHeaderBytes(sampleRate, channels, bitsPerSample, isFloat, 0L))
        while (buf.hasRemaining()) channel.write(buf)
    }

    fun patchWavHeaderSizes(channel: FileChannel, dataByteCount: Long) {
        val clampedData = min(dataByteCount.coerceAtLeast(0L), 0xFFFF_FF00L)
        val riffSize = clampedData + 36L
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)

        buf.putInt(riffSize.toInt())
        buf.flip()
        channel.write(buf, 4L)

        buf.clear()
        buf.putInt(clampedData.toInt())
        buf.flip()
        channel.write(buf, 40L)
    }

    fun writeFloatSamples(
        channel: FileChannel,
        samples: FloatArray,
        sampleCount: Int,
        writeAsFloat32: Boolean
    ): Long {
        val bb = if (writeAsFloat32) {
            ByteBuffer.allocate(sampleCount * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                for (i in 0 until sampleCount) putFloat(samples[i].coerceIn(-1f, 1f))
                flip()
            }
        } else {
            ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                for (i in 0 until sampleCount) {
                    val clamped = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
                    putShort(clamped.toShort())
                }
                flip()
            }
        }
        val total = bb.remaining().toLong()
        while (bb.hasRemaining()) {
            channel.write(bb)
        }
        return total
    }

    /**
     * Checks if a WAV file opened via [channel] has placeholder/unfinalized header sizes
     * (e.g., after an abrupt service kill or crash) and repairs offsets 4 & 40 in-place.
     * Returns the repaired data byte count if a repair was performed, or null if already valid.
     */
    fun repairWavHeaderIfNeeded(channel: FileChannel): Long? {
        val fileSize = channel.size()
        if (fileSize <= 44L) return null
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        channel.read(header, 0L)
        if (header.position() < 44) return null
        val bytes = header.array()
        val riffTag = String(bytes, 0, 4, Charsets.US_ASCII)
        val waveTag = String(bytes, 8, 4, Charsets.US_ASCII)
        val dataTag = String(bytes, 36, 4, Charsets.US_ASCII)
        if (riffTag != "RIFF" || waveTag != "WAVE" || dataTag != "data") return null

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val riffSize = bb.getInt(4).toLong() and 0xFFFF_FFFFL
        val dataSize = bb.getInt(40).toLong() and 0xFFFF_FFFFL
        val blockAlign = (bb.getShort(32).toInt() and 0xFFFF).coerceAtLeast(1)
        val rawDataBytes = fileSize - 44L
        val alignedDataBytes = rawDataBytes - (rawDataBytes % blockAlign)

        val needsRepair = dataSize == 0L || riffSize <= 36L ||
            (dataSize + 44L < fileSize - 64L && riffSize < fileSize - 16L)
        if (!needsRepair || alignedDataBytes <= 0L) return null

        patchWavHeaderSizes(channel, alignedDataBytes)
        channel.force(true)
        return alignedDataBytes
    }

    private fun isValidWavParams(
        formatCode: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        dataSize: Long
    ): Boolean {
        val supportedFmt = formatCode == FORMAT_PCM || formatCode == FORMAT_IEEE_FLOAT || formatCode == 0xFFFE
        val validRate = sampleRate in 4000..384000
        val validChannels = channels in 1..8
        val validBits = bitsPerSample in setOf(8, 16, 24, 32)
        return supportedFmt && validRate && validChannels && validBits && dataSize > 0L
    }

    private fun readChannelFully(channel: FileChannel, dst: ByteBuffer, startOffset: Long): Int {
        var pos = startOffset
        var total = 0
        while (dst.hasRemaining()) {
            val n = channel.read(dst, pos)
            if (n <= 0) break
            pos += n
            total += n
        }
        return total
    }

    private fun readStreamFully(input: InputStream, buf: ByteArray, offset: Int = 0, len: Int = buf.size): Int {
        var total = 0
        while (total < len) {
            val n = input.read(buf, offset + total, len - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun skipStreamBytes(input: InputStream, bytesToSkip: Long): Boolean {
        var remaining = bytesToSkip
        val scratch = ByteArray(4096)
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val r = input.read(scratch, 0, min(remaining, scratch.size.toLong()).toInt())
                if (r < 0) return false
                remaining -= r
            }
        }
        return true
    }

    fun parseWavInfo(channel: FileChannel): WavInfo? {
        val totalSize = runCatching { channel.size() }.getOrDefault(-1L)
        if (totalSize < 44L) return null
        return try {
            val riff = ByteBuffer.allocate(12)
            if (readChannelFully(channel, riff, 0L) != 12) return null
            val riffArr = riff.array()
            if (String(riffArr, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(riffArr, 8, 4, Charsets.US_ASCII) != "WAVE"
            ) return null

            var formatCode = 0
            var sampleRate = 0
            var channels = 0
            var bitsPerSample = 0
            var isFloat = false
            var foundFmt = false
            var dataOffset = -1L
            var dataSize = 0L
            var pos = 12L

            val chunkHdr = ByteBuffer.allocate(8)
            while (pos + 8 <= totalSize) {
                chunkHdr.clear()
                if (readChannelFully(channel, chunkHdr, pos) != 8) break
                pos += 8
                val hdrArr = chunkHdr.array()
                val chunkId = String(hdrArr, 0, 4, Charsets.US_ASCII)
                val chunkSize = ByteBuffer.wrap(hdrArr, 4, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL

                when (chunkId) {
                    "fmt " -> {
                        if (chunkSize < 16L) return null
                        val readLen = min(chunkSize, 40L).toInt()
                        val fmtBuf = ByteBuffer.allocate(readLen)
                        if (readChannelFully(channel, fmtBuf, pos) < 16) return null
                        val bb = ByteBuffer.wrap(fmtBuf.array()).order(ByteOrder.LITTLE_ENDIAN)
                        formatCode = bb.short.toInt() and 0xFFFF
                        channels = bb.short.toInt() and 0xFFFF
                        sampleRate = bb.int
                        bb.int
                        bb.short
                        bitsPerSample = bb.short.toInt() and 0xFFFF
                        isFloat = formatCode == FORMAT_IEEE_FLOAT ||
                            (formatCode == 0xFFFE && bitsPerSample == 32)
                        foundFmt = true
                        pos += chunkSize + (chunkSize and 1L)
                    }
                    "data" -> {
                        dataOffset = pos
                        dataSize = min(chunkSize, (totalSize - dataOffset).coerceAtLeast(0L))
                        break
                    }
                    else -> {
                        pos = min(totalSize, pos + chunkSize + (chunkSize and 1L))
                    }
                }
            }
            if (!foundFmt || dataOffset < 0 || !isValidWavParams(formatCode, sampleRate, channels, bitsPerSample, dataSize)) {
                return null
            }
            WavInfo(sampleRate, channels, bitsPerSample, isFloat, dataOffset, dataSize)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses RIFF/WAVE header sequentially from an [InputStream] (for non-seekable ContentResolver streams).
     */
    fun parseWavInfo(inputStream: InputStream, totalBytesHint: Long = -1L): WavInfo? {
        return try {
            val riff = ByteArray(12)
            if (readStreamFully(inputStream, riff) != 12) return null
            if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(riff, 8, 4, Charsets.US_ASCII) != "WAVE"
            ) return null

            var formatCode = 0
            var sampleRate = 0
            var channels = 0
            var bitsPerSample = 0
            var isFloat = false
            var foundFmt = false
            var dataOffset = -1L
            var dataSize = 0L
            var pos = 12L

            val chunkHdr = ByteArray(8)
            while (readStreamFully(inputStream, chunkHdr) == 8) {
                pos += 8
                val chunkId = String(chunkHdr, 0, 4, Charsets.US_ASCII)
                val chunkSize = ByteBuffer.wrap(chunkHdr, 4, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL

                when (chunkId) {
                    "fmt " -> {
                        if (chunkSize < 16L) return null
                        val readLen = min(chunkSize, 40L).toInt()
                        val fmtBytes = ByteArray(readLen)
                        if (readStreamFully(inputStream, fmtBytes) < 16) return null
                        val bb = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
                        formatCode = bb.short.toInt() and 0xFFFF
                        channels = bb.short.toInt() and 0xFFFF
                        sampleRate = bb.int
                        bb.int
                        bb.short
                        bitsPerSample = bb.short.toInt() and 0xFFFF
                        isFloat = formatCode == FORMAT_IEEE_FLOAT ||
                            (formatCode == 0xFFFE && bitsPerSample == 32)
                        foundFmt = true
                        val rem = (chunkSize - readLen) + (chunkSize and 1L)
                        if (rem > 0L && !skipStreamBytes(inputStream, rem)) return null
                        pos += chunkSize + (chunkSize and 1L)
                    }
                    "data" -> {
                        dataOffset = pos
                        val maxAvail = if (totalBytesHint > dataOffset) totalBytesHint - dataOffset else chunkSize
                        dataSize = min(chunkSize, maxAvail)
                        break
                    }
                    else -> {
                        val padded = chunkSize + (chunkSize and 1L)
                        if (padded > 0L && !skipStreamBytes(inputStream, padded)) break
                        pos += padded
                    }
                }
            }
            if (!foundFmt || dataOffset < 0 || !isValidWavParams(formatCode, sampleRate, channels, bitsPerSample, dataSize)) {
                return null
            }
            WavInfo(sampleRate, channels, bitsPerSample, isFloat, dataOffset, dataSize)
        } catch (_: Exception) {
            null
        }
    }

    fun appendCueChunks(channel: FileChannel, cuePoints: List<WavCuePoint>): Boolean {
        if (cuePoints.isEmpty()) return false
        return try {
            val info = parseWavInfo(channel) ?: return false
            val dataEnd = info.dataOffset + info.dataSize + (info.dataSize and 1L)
            channel.truncate(dataEnd)
            channel.position(dataEnd)

            val cueDataSize = 4 + cuePoints.size * 24
            val cueBuf = ByteBuffer.allocate(8 + cueDataSize).order(ByteOrder.LITTLE_ENDIAN)
            cueBuf.put("cue ".toByteArray(Charsets.US_ASCII))
            cueBuf.putInt(cueDataSize)
            cueBuf.putInt(cuePoints.size)
            for (cp in cuePoints) {
                val samplePos = cp.sampleOffset.coerceIn(0L, info.totalFrames).toInt()
                cueBuf.putInt(cp.id)
                cueBuf.putInt(samplePos)
                cueBuf.put("data".toByteArray(Charsets.US_ASCII))
                cueBuf.putInt(0)
                cueBuf.putInt(0)
                cueBuf.putInt(samplePos)
            }
            cueBuf.flip()
            while (cueBuf.hasRemaining()) channel.write(cueBuf)

            val labelChunks = cuePoints.map { cp ->
                val textBytes = (cp.label.ifBlank { "Marker ${cp.id}" } + "\u0000")
                    .toByteArray(Charsets.UTF_8)
                val subSize = 4 + textBytes.size
                val paddedSize = subSize + (subSize and 1)
                val b = ByteBuffer.allocate(8 + paddedSize).order(ByteOrder.LITTLE_ENDIAN)
                b.put("labl".toByteArray(Charsets.US_ASCII))
                b.putInt(subSize)
                b.putInt(cp.id)
                b.put(textBytes)
                if ((subSize and 1) == 1) b.put(0)
                b.array()
            }
            val listPayloadSize = 4 + labelChunks.sumOf { it.size }
            val listHeader = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            listHeader.put("LIST".toByteArray(Charsets.US_ASCII))
            listHeader.putInt(listPayloadSize)
            listHeader.put("adtl".toByteArray(Charsets.US_ASCII))
            listHeader.flip()
            while (listHeader.hasRemaining()) channel.write(listHeader)
            labelChunks.forEach { arr ->
                val bb = ByteBuffer.wrap(arr)
                while (bb.hasRemaining()) channel.write(bb)
            }

            val newRiffSize = (channel.size() - 8L).coerceAtMost(0xFFFF_FF00L).toInt()
            val sizeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(newRiffSize)
                flip()
            }
            channel.write(sizeBuf, 4L)
            channel.force(true)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Parses any standard RIFF/WAVE file, skipping unknown chunks (LIST, JUNK, cue, etc.)
     * to locate `fmt ` and `data` offsets accurately.
     */
    fun parseWavInfo(file: File): WavInfo? {
        if (!file.exists() || file.length() < 44) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val riff = ByteArray(12)
                if (raf.read(riff) != 12) return null
                val riffTag = String(riff, 0, 4, Charsets.US_ASCII)
                val waveTag = String(riff, 8, 4, Charsets.US_ASCII)
                if (riffTag != "RIFF" || waveTag != "WAVE") return null

                var formatCode = 0
                var sampleRate = 0
                var channels = 0
                var bitsPerSample = 0
                var isFloat = false
                var foundFmt = false
                var dataOffset = -1L
                var dataSize = 0L

                val chunkHeader = ByteArray(8)
                while (raf.filePointer + 8 <= raf.length()) {
                    if (raf.read(chunkHeader) != 8) break
                    val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                    val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4)
                        .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL

                    when (chunkId) {
                        "fmt " -> {
                            if (chunkSize < 16L) return null
                            val fmtBytes = ByteArray(min(chunkSize, 40L).toInt())
                            raf.readFully(fmtBytes)
                            val bb = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
                            formatCode = bb.short.toInt() and 0xFFFF
                            channels = bb.short.toInt() and 0xFFFF
                            sampleRate = bb.int
                            bb.int // byteRate
                            bb.short // blockAlign
                            bitsPerSample = bb.short.toInt() and 0xFFFF
                            isFloat = formatCode == FORMAT_IEEE_FLOAT ||
                                (formatCode == 0xFFFE && bitsPerSample == 32)
                            foundFmt = true
                            val rem = (chunkSize - fmtBytes.size) + (chunkSize and 1L)
                            if (rem > 0) raf.seek(raf.filePointer + rem)
                        }
                        "data" -> {
                            dataOffset = raf.filePointer
                            dataSize = min(chunkSize, (raf.length() - dataOffset).coerceAtLeast(0L))
                            break
                        }
                        else -> {
                            val padded = chunkSize + (chunkSize and 1L)
                            raf.seek(min(raf.length(), raf.filePointer + padded))
                        }
                    }
                }
                if (!foundFmt || dataOffset < 0 || !isValidWavParams(formatCode, sampleRate, channels, bitsPerSample, dataSize)) {
                    return null
                }
                WavInfo(sampleRate, channels, bitsPerSample, isFloat, dataOffset, dataSize)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads [frameCount] interleaved frames starting at [startFrame] into normalized float samples [-1f..1f].
     * Returns number of frames actually read.
     */
    fun readFramesAsFloat(
        raf: RandomAccessFile,
        info: WavInfo,
        startFrame: Long,
        frameCount: Int,
        outInterleaved: FloatArray
    ): Int {
        if (startFrame < 0 || startFrame >= info.totalFrames || frameCount <= 0) return 0
        val readableFrames = min(frameCount.toLong(), info.totalFrames - startFrame).toInt()
        val byteOffset = info.dataOffset + startFrame * info.frameSizeBytes
        val bytesToRead = readableFrames * info.frameSizeBytes
        val raw = ByteArray(bytesToRead)
        raf.seek(byteOffset)
        val actuallyRead = raf.read(raw)
        if (actuallyRead <= 0) return 0
        return decodePcmBytesToFloat(raw, actuallyRead, info, outInterleaved)
    }

    /**
     * Reads [frameCount] interleaved frames starting at [startFrame] from a seekable [FileChannel]
     * (works with both direct files and ContentResolver ParcelFileDescriptors for MediaStore / SAF URIs).
     */
    fun readFramesAsFloat(
        channel: FileChannel,
        info: WavInfo,
        startFrame: Long,
        frameCount: Int,
        outInterleaved: FloatArray
    ): Int {
        if (startFrame < 0 || startFrame >= info.totalFrames || frameCount <= 0) return 0
        val readableFrames = min(frameCount.toLong(), info.totalFrames - startFrame).toInt()
        val byteOffset = info.dataOffset + startFrame * info.frameSizeBytes
        val bytesToRead = readableFrames * info.frameSizeBytes
        val raw = ByteArray(bytesToRead)
        val actuallyRead = readChannelFully(channel, ByteBuffer.wrap(raw), byteOffset)
        if (actuallyRead <= 0) return 0
        return decodePcmBytesToFloat(raw, actuallyRead, info, outInterleaved)
    }

    /**
     * Reads [frameCount] interleaved frames starting at [startFrame] sequentially from an [InputStream]
     * positioned at byte 0 of the WAV file (fallback for non-seekable ContentResolver streams).
     */
    fun readFramesAsFloat(
        inputStream: InputStream,
        info: WavInfo,
        startFrame: Long,
        frameCount: Int,
        outInterleaved: FloatArray
    ): Int {
        if (startFrame < 0 || startFrame >= info.totalFrames || frameCount <= 0) return 0
        val readableFrames = min(frameCount.toLong(), info.totalFrames - startFrame).toInt()
        val byteOffset = info.dataOffset + startFrame * info.frameSizeBytes
        if (!skipStreamBytes(inputStream, byteOffset)) return 0
        val bytesToRead = readableFrames * info.frameSizeBytes
        val raw = ByteArray(bytesToRead)
        val actuallyRead = readStreamFully(inputStream, raw)
        if (actuallyRead <= 0) return 0
        return decodePcmBytesToFloat(raw, actuallyRead, info, outInterleaved)
    }

    private fun decodePcmBytesToFloat(
        raw: ByteArray,
        actuallyRead: Int,
        info: WavInfo,
        outInterleaved: FloatArray
    ): Int {
        val framesRead = actuallyRead / info.frameSizeBytes
        if (framesRead <= 0) return 0
        val sampleCount = framesRead * info.channels
        val bb = ByteBuffer.wrap(raw, 0, framesRead * info.frameSizeBytes).order(ByteOrder.LITTLE_ENDIAN)

        when {
            info.isFloat && info.bitsPerSample == 32 -> {
                for (i in 0 until sampleCount) {
                    outInterleaved[i] = bb.float
                }
            }
            info.bitsPerSample == 16 -> {
                for (i in 0 until sampleCount) {
                    outInterleaved[i] = bb.short / 32768f
                }
            }
            info.bitsPerSample == 24 -> {
                var idx = 0
                for (i in 0 until sampleCount) {
                    val b0 = raw[idx].toInt() and 0xFF
                    val b1 = raw[idx + 1].toInt() and 0xFF
                    val b2 = raw[idx + 2].toInt()
                    val v = (b2 shl 16) or (b1 shl 8) or b0
                    outInterleaved[i] = v / 8388608f
                    idx += 3
                }
            }
            info.bitsPerSample == 32 -> {
                for (i in 0 until sampleCount) {
                    outInterleaved[i] = (bb.int / 2147483648.0).toFloat()
                }
            }
            else -> {
                for (i in 0 until sampleCount) {
                    outInterleaved[i] = ((raw[i].toInt() and 0xFF) - 128) / 128f
                }
            }
        }
        return framesRead
    }

    /**
     * Writes interleaved normalized float samples into WAV file as 16-bit PCM or 32-bit IEEE Float.
     */
    fun writeFloatSamples(
        raf: RandomAccessFile,
        samples: FloatArray,
        sampleCount: Int,
        writeAsFloat32: Boolean
    ): Long {
        return if (writeAsFloat32) {
            val bb = ByteBuffer.allocate(sampleCount * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until sampleCount) {
                bb.putFloat(samples[i].coerceIn(-1f, 1f))
            }
            val arr = bb.array()
            raf.write(arr)
            arr.size.toLong()
        } else {
            val bb = ByteBuffer.allocate(sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until sampleCount) {
                val clamped = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
                bb.putShort(clamped.toShort())
            }
            val arr = bb.array()
            raf.write(arr)
            arr.size.toLong()
        }
    }

    /**
     * Appends standard RIFF WAV `cue ` and `LIST/adtl/labl` chunks to the end of a WAV file
     * so timestamp markers travel inside the WAV file itself in DAWs and audio editors.
     */
    fun appendCueChunks(wavFile: File, cuePoints: List<WavCuePoint>): Boolean {
        if (!wavFile.exists() || cuePoints.isEmpty()) return false
        return try {
            RandomAccessFile(wavFile, "rw").use { raf ->
                val info = parseWavInfo(wavFile) ?: return false
                // Seek right after the audio data chunk (padded to even boundary)
                val dataEnd = info.dataOffset + info.dataSize + (info.dataSize and 1L)
                raf.setLength(dataEnd)
                raf.seek(dataEnd)

                // Build `cue ` chunk: 4 bytes count + 24 bytes per cue point
                val cueDataSize = 4 + cuePoints.size * 24
                val cueBuf = ByteBuffer.allocate(8 + cueDataSize).order(ByteOrder.LITTLE_ENDIAN)
                cueBuf.put("cue ".toByteArray(Charsets.US_ASCII))
                cueBuf.putInt(cueDataSize)
                cueBuf.putInt(cuePoints.size)
                for (cp in cuePoints) {
                    val samplePos = cp.sampleOffset.coerceIn(0L, info.totalFrames).toInt()
                    cueBuf.putInt(cp.id)
                    cueBuf.putInt(samplePos)
                    cueBuf.put("data".toByteArray(Charsets.US_ASCII))
                    cueBuf.putInt(0) // chunkStart
                    cueBuf.putInt(0) // blockStart
                    cueBuf.putInt(samplePos) // sampleOffset
                }
                raf.write(cueBuf.array())

                // Build `LIST` (`adtl`) chunk with `labl` subchunks
                val labelChunks = cuePoints.map { cp ->
                    val textBytes = (cp.label.ifBlank { "Marker ${cp.id}" } + "\u0000")
                        .toByteArray(Charsets.UTF_8)
                    val subSize = 4 + textBytes.size
                    val paddedSize = subSize + (subSize and 1)
                    val b = ByteBuffer.allocate(8 + paddedSize).order(ByteOrder.LITTLE_ENDIAN)
                    b.put("labl".toByteArray(Charsets.US_ASCII))
                    b.putInt(subSize)
                    b.putInt(cp.id)
                    b.put(textBytes)
                    if ((subSize and 1) == 1) b.put(0)
                    b.array()
                }
                val listPayloadSize = 4 + labelChunks.sumOf { it.size }
                val listHeader = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                listHeader.put("LIST".toByteArray(Charsets.US_ASCII))
                listHeader.putInt(listPayloadSize)
                listHeader.put("adtl".toByteArray(Charsets.US_ASCII))
                raf.write(listHeader.array())
                labelChunks.forEach { raf.write(it) }

                // Update top-level RIFF size at offset 4
                val newRiffSize = (raf.length() - 8L).coerceAtMost(0xFFFF_FF00L).toInt()
                raf.seek(4L)
                val sizeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                sizeBuf.putInt(newRiffSize)
                raf.write(sizeBuf.array())
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Generates a downsampled peak-amplitude waveform envelope for UI rendering
     * without loading the entire WAV file into memory.
     */
    fun extractWaveformPeaks(file: File, numBins: Int = 240): FloatArray {
        val info = parseWavInfo(file) ?: return FloatArray(numBins)
        if (info.totalFrames <= 0 || numBins <= 0) return FloatArray(numBins)
        val peaks = FloatArray(numBins)
        val framesPerBin = max(1L, info.totalFrames / numBins)
        val chunkFrames = min(framesPerBin, 4096L).toInt()
        val buf = FloatArray(chunkFrames * info.channels)

        try {
            RandomAccessFile(file, "r").use { raf ->
                for (bin in 0 until numBins) {
                    val startFrame = bin * framesPerBin
                    val read = readFramesAsFloat(raf, info, startFrame, chunkFrames, buf)
                    var maxVal = 0f
                    val samples = read * info.channels
                    for (i in 0 until samples) {
                        val a = kotlin.math.abs(buf[i])
                        if (a > maxVal) maxVal = a
                    }
                    peaks[bin] = maxVal.coerceIn(0f, 1f)
                }
            }
        } catch (_: Exception) {
        }
        return peaks
    }
}
