package com.droiddeck.launcher.input

import android.util.Log

class HeldInput(private val emit: (Int, Boolean) -> Unit) {
    private val owners = IntArray(768)

    @Synchronized
    fun set(source: Int, code: Int, down: Boolean) {
        if (code !in owners.indices) return
        val before = owners[code]
        val after = if (down) before or source else before and source.inv()
        owners[code] = after
        if (down && before != 0 && before and source == 0)
            Log.w("HeldInput", "press $code from $source ignored, still held by $before")
        if ((before == 0) != (after == 0)) emit(code, after != 0)
    }

    @Synchronized
    fun release(source: Int) {
        for (code in owners.indices) if (owners[code] and source != 0) set(source, code, false)
    }

    @Synchronized
    fun clear() {
        for (code in owners.indices) if (owners[code] != 0) {
            owners[code] = 0
            emit(code, false)
        }
    }
}
