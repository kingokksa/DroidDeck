package com.droiddeck.launcher.input

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

class VirtualMouse private constructor(private val map: ByteBuffer) {
    private var buttons = 0

    fun move(dx: Int, dy: Int) {
        var x = dx
        var y = dy
        while (x != 0 || y != 0) {
            val stepX = x.coerceIn(-127, 127)
            val stepY = y.coerceIn(-127, 127)
            x -= stepX
            y -= stepY
            publish(stepX, stepY, 0)
        }
    }

    fun button(code: Int, down: Boolean) {
        val bit = when (code) {
            PointerGestures.BTN_LEFT -> 1
            PointerGestures.BTN_RIGHT -> 2
            PointerGestures.BTN_MIDDLE -> 4
            else -> return
        }
        buttons = if (down) buttons or bit else buttons and bit.inv()
        publish(0, 0, 0)
    }

    fun wheel(steps: Int) {
        var left = steps
        while (left != 0) {
            val step = left.coerceIn(-1, 1)
            left -= step
            publish(0, 0, step)
        }
    }

    @Synchronized
    fun handle(type: Int, a: Int, b: Int) {
        when (type) {
            3 -> button(a, b != 0)
            4 -> wheel(a)
            6 -> move(Math.round(a / 256f), Math.round(b / 256f))
        }
    }

    fun unplug() {
        map.putInt(OFF_PLUGGED, 0)
    }

    private fun publish(x: Int, y: Int, wheel: Int) {
        val seq = map.getLong(OFF_SEQ)
        map.putLong(OFF_SEQ, seq + 1)
        map.put(OFF_REPORT, buttons.toByte())
        map.put(OFF_REPORT + 1, x.toByte())
        map.put(OFF_REPORT + 2, y.toByte())
        map.put(OFF_REPORT + 3, wheel.toByte())
        map.putLong(OFF_SEQ, seq + 2)
    }

    companion object {
        private const val TAG = "VirtualMouse"
        private const val SIZE = 256
        private const val MAGIC = 0x3145534d
        private const val VERSION = 1
        private const val OFF_MAGIC = 0
        private const val OFF_VERSION = 4
        private const val OFF_SEQ = 8
        private const val OFF_REPORT = 16
        private const val OFF_PLUGGED = 20
        private const val OFF_OPENED = 24

        @Volatile
        private var current: VirtualMouse? = null

        fun ringFile(fakeInputDir: File) = File(fakeInputDir, "mouse")

        @Synchronized
        fun prepare(fakeInputDir: File): File? = try {
            val file = ringFile(fakeInputDir)
            file.delete()
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(SIZE.toLong())
                val map = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, SIZE.toLong()).order(ByteOrder.LITTLE_ENDIAN)
                map.putInt(OFF_VERSION, VERSION)
                map.putInt(OFF_PLUGGED, 1)
                map.putInt(OFF_OPENED, 0)
                map.putLong(OFF_SEQ, 0)
                map.putInt(OFF_MAGIC, MAGIC)
                current = VirtualMouse(map)
            }
            file.setReadable(true, false)
            file.setWritable(true, false)
            file
        } catch (e: Exception) {
            Log.w(TAG, "virtual mouse: no shared file: $e")
            current = null
            null
        }

        @JvmStatic
        fun send(type: Int, a: Int, b: Int) {
            current?.handle(type, a, b)
        }

        @Synchronized
        fun release() {
            current?.unplug()
            current = null
        }
    }
}
