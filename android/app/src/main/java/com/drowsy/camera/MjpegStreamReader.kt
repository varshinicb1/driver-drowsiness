package com.drowsy.camera

import okio.BufferedSource
import java.io.IOException

/**
 * Extracts JPEG frames from an MJPEG multipart stream (ESP32 /stream).
 * Uses a ring-style grow buffer with tail trimming to avoid per-frame copies of the full backlog.
 */
internal class MjpegStreamReader(private val source: BufferedSource) {

    private var buffer = ByteArray(INITIAL_CAP)
    private var size = 0

    @Throws(IOException::class)
    fun nextJpeg(): ByteArray? {
        while (true) {
            val frame = extractFrame()
            if (frame != null) return frame
            if (!readMore()) return null
        }
    }

    private fun readMore(): Boolean {
        if (!source.isOpen) return false
        ensureCapacity(size + READ_CHUNK)
        val read = source.read(buffer, size, READ_CHUNK)
        if (read == -1) return false
        size += read
        return true
    }

    private fun extractFrame(): ByteArray? {
        if (size < 4) return null
        var start = -1
        var end = -1
        var i = 0
        while (i < size - 1) {
            if (buffer[i] == 0xFF.toByte() && buffer[i + 1] == 0xD8.toByte()) start = i
            if (buffer[i] == 0xFF.toByte() && buffer[i + 1] == 0xD9.toByte() && start >= 0) {
                end = i + 1
                break
            }
            i++
        }
        if (start < 0 || end < start) {
            if (size > MAX_BACKLOG) compactTail()
            return null
        }
        val jpeg = buffer.copyOfRange(start, end + 1)
        val remain = size - (end + 1)
        if (remain > 0) {
            System.arraycopy(buffer, end + 1, buffer, 0, remain)
        }
        size = remain
        return jpeg
    }

    private fun compactTail() {
        val keep = minOf(MAX_BACKLOG / 4, size)
        if (keep < size) {
            System.arraycopy(buffer, size - keep, buffer, 0, keep)
            size = keep
        }
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= buffer.size) return
        var cap = buffer.size
        while (cap < needed) cap *= 2
        buffer = buffer.copyOf(cap)
    }

    companion object {
        private const val INITIAL_CAP = 64 * 1024
        private const val READ_CHUNK = 8 * 1024
        private const val MAX_BACKLOG = 256 * 1024
    }
}
