package com.islandyout.tapforge

import rikka.shizuku.Shizuku
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Records real touches while the screen underneath stays fully visible and
 * interactive — no overlay involved. Works by streaming raw kernel input
 * events (`getevent -lt`) from the touchscreen device through a Shizuku
 * shell process, parsing multitouch slot events into (x, y, down/up) with
 * real timestamps, and converting them into ClickerService.RecordedAction.
 *
 * Coordinates from /dev/input are in the touch panel's raw range, which is
 * usually — but not always — 1:1 with screen pixels. We calibrate against
 * the real display size using ABS_MT_POSITION_X/Y max values reported by
 * `getevent -i`, so taps land in the right place regardless of device.
 */
class RawInputRecorder(
    private val screenWidthPx: Int,
    private val screenHeightPx: Int,
    private val onEvent: (RecordedAction) -> Unit,
    private val onError: (String) -> Unit
) {
    data class RecordedAction(
        val type: String, // "tap" | "hold" | "swipe"
        val x1: Int, val y1: Int,
        val x2: Int = 0, val y2: Int = 0,
        val delayMs: Long,
        val durationMs: Long
    )

    @Volatile private var running = false
    private var thread: Thread? = null
    private var rawMaxX = 0
    private var rawMaxY = 0

    fun start() {
        if (!ShizukuBridge.hasPermission()) {
            onError("Shizuku permission not granted")
            return
        }
        val device = ShizukuBridge.findTouchDevice()
        if (device == null) {
            onError("Couldn't find the touchscreen input device")
            return
        }
        readRawRanges(device)

        running = true
        thread = Thread {
            try {
                streamEvents(device)
            } catch (t: Throwable) {
                onError("Recording stopped: ${t.message}")
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun readRawRanges(device: String) {
        val info = ShizukuBridge.exec("getevent -i $device")
        // Lines look like: "    ABS_MT_POSITION_X    : value 0, min 0, max 4095, ..."
        Regex("ABS_MT_POSITION_X\\s*:.*max (\\d+)").find(info)?.let { rawMaxX = it.groupValues[1].toIntOrNull() ?: 0 }
        Regex("ABS_MT_POSITION_Y\\s*:.*max (\\d+)").find(info)?.let { rawMaxY = it.groupValues[1].toIntOrNull() ?: 0 }
    }

    private fun toScreenX(raw: Int) = if (rawMaxX > 0) (raw.toFloat() / rawMaxX * screenWidthPx).roundToInt() else raw
    private fun toScreenY(raw: Int) = if (rawMaxY > 0) (raw.toFloat() / rawMaxY * screenHeightPx).roundToInt() else raw

    /**
     * Streams `getevent -lt <device>` and parses a single-finger touch
     * sequence. Multitouch slots beyond finger 0 are ignored — this app
     * automates single-finger taps/swipes, not multi-finger gestures.
     */
    private fun streamEvents(device: String) {
        val process = Shizuku.newProcess(arrayOf("sh", "-c", "getevent -lt $device"), null, null)
        val reader = process.inputStream.bufferedReader()

        var curX = 0; var curY = 0
        var downTime = 0L
        var downX = 0; var downY = 0
        var touching = false
        var lastEventStart = System.currentTimeMillis()
        var lastLineTime = 0.0

        try {
            var line: String?
            while (running && reader.readLine().also { line = it } != null) {
                val l = line ?: continue
                // Format: "[   12345.678901] EV_ABS       ABS_MT_POSITION_X   0000015e"
                val tsMatch = Regex("\\[\\s*(\\d+\\.\\d+)\\]").find(l) ?: continue
                lastLineTime = tsMatch.groupValues[1].toDoubleOrNull() ?: lastLineTime
                val nowMs = System.currentTimeMillis() // wall clock is fine; kernel ts only used for relative ordering

                when {
                    l.contains("ABS_MT_POSITION_X") -> {
                        val hex = l.trim().substringAfterLast(' ')
                        curX = toScreenX(hex.toIntOrNull(16) ?: curX)
                    }
                    l.contains("ABS_MT_POSITION_Y") -> {
                        val hex = l.trim().substringAfterLast(' ')
                        curY = toScreenY(hex.toIntOrNull(16) ?: curY)
                    }
                    l.contains("BTN_TOUCH") && l.contains("DOWN") || l.contains("ABS_MT_TRACKING_ID") && !l.trim().endsWith("ffffffff") -> {
                        if (!touching) {
                            touching = true
                            downTime = nowMs
                            downX = curX; downY = curY
                        }
                    }
                    l.contains("BTN_TOUCH") && l.contains("UP") || l.contains("ABS_MT_TRACKING_ID") && l.trim().endsWith("ffffffff") -> {
                        if (touching) {
                            touching = false
                            val dur = nowMs - downTime
                            val dist = hypot((curX - downX).toDouble(), (curY - downY).toDouble())
                            val delay = (downTime - lastEventStart).coerceAtLeast(0)
                            lastEventStart = downTime

                            val action = when {
                                dist > 24 -> RecordedAction("swipe", downX, downY, curX, curY, delay, dur.coerceAtLeast(20))
                                dur >= 450 -> RecordedAction("hold", downX, downY, delayMs = delay, durationMs = dur)
                                else -> RecordedAction("tap", downX, downY, delayMs = delay, durationMs = 30)
                            }
                            onEvent(action)
                        }
                    }
                }
            }
        } finally {
            try { process.destroy() } catch (_: Exception) {}
        }
    }
}
