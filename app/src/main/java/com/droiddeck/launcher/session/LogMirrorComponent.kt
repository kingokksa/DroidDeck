package com.droiddeck.launcher.session

import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import java.io.File
import java.io.RandomAccessFile

class LogMirrorComponent(private val dir: File) : SessionPart() {
    private var thread: Thread? = null

    @Volatile
    private var running = false

    override fun start() {
        running = true
        Log.i(PAD_TAG, "mirroring " + dir.path)
        thread = Thread({ mirror() }, "log-mirror").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        running = false
        thread?.join(400)
        thread = null
    }

    private fun mirror() {
        val pad = Track(File(dir, "pad.log"), PAD_TAG, null)
        val guest = Track(File(dir, "session.log"), GUEST_TAG, INTERESTING)
        while (running) {
            pad.pump()
            guest.pump()
            try {
                Thread.sleep(400)
            } catch (e: InterruptedException) {
                return
            }
        }
        pad.pump()
        guest.pump()
    }

    private class Track(private val file: File, private val tag: String, private val filter: Regex?) {
        private var offset = 0L
        private var partial = StringBuilder()

        fun pump() {
            if (!file.isFile) return
            try {
                RandomAccessFile(file, "r").use { raf ->
                    if (raf.length() < offset) {
                        offset = 0
                        partial = StringBuilder()
                    }
                    if (raf.length() == offset) return
                    raf.seek(offset)
                    val buffer = ByteArray(64 * 1024)
                    val read = raf.read(buffer)
                    if (read <= 0) return
                    offset += read
                    partial.append(String(buffer, 0, read, Charsets.UTF_8))
                }
            } catch (e: Exception) {
                return
            }
            var end = partial.indexOf("\n")
            while (end >= 0) {
                val line = partial.substring(0, end)
                partial.delete(0, end + 1)
                emit(line)
                end = partial.indexOf("\n")
            }
            if (partial.length > MAX_LINE) {
                emit(partial.toString())
                partial = StringBuilder()
            }
        }

        private fun emit(line: String) {
            if (line.isBlank()) return
            if (filter != null && !filter.containsMatchIn(line)) return
            Log.i(tag, if (line.length > MAX_LINE) line.substring(0, MAX_LINE) else line)
        }
    }

    companion object {
        private const val PAD_TAG = "PadLog"
        private const val GUEST_TAG = "GuestLog"
        private const val MAX_LINE = 3500
        private val INTERESTING =
            Regex("hidraw|mouse|winebus|dinput|rawinput|0dde|Watch_Dogs|err:|unhandled", RegexOption.IGNORE_CASE)
    }
}
