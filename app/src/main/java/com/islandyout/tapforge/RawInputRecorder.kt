package com.islandyout.tapforge

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Records real touches while the screen underneath stays fully visible and
 * interactive — no overlay. Streams raw kernel input events (`getevent -lt`)
 * from the touchscreen through Shizuku's privileged UserService, parses
 * multitouch events into (x, y, down/up), and emits RecordedAction.
 *
 * Coordinates from /dev/input are in the panel's raw range, calibrated to the
 * real display size via ABS_MT_POSITION_X/Y max from `getevent -i`.
 *
 * `getevent -lt <dev>` lines look like:
 *   [   12345.678901] EV_ABS       ABS_MT_POSITION_X    000004a2
 *   [   12345.678920] EV_KEY       BTN_TOUCH            DOWN
 *   [   12345.681000] EV_ABS       ABS_MT_TRACKING_ID   ffffffff
 * The value is always the last whitespace token; we read it as hex.
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
    private var sessionId = -1
    private var rawMaxX = 0
    private var rawMaxY = 0

    // touch-tracking state (single finger)
    private var curX = 0
    private var curY = 0
    private var touching = false
    private var downTime = 0L
    private var downX = 0
    private var downY = 0
    private var lastEventStart = 0L

    fun start() {
        if (!ShizukuBridge.hasPermission()) {
            onError("Shizuku permission not granted")
            return
        }
        thread = Thread {
            try {
                val device = ShizukuBridge.findTouchDevice()
                if (device == null) {
                    onError("Couldn't find the touchscreen input device")
                    return@Thread
                }
                readRawRanges(device)

                sessionId = ShizukuBridge.startStream("getevent -lt $device")
                if (sessionId < 0) {
                    onError("Couldn't start the input stream")
                    return@Thread
                }
                running = true
                lastEventStart = System.currentTimeMillis()
                pollLoop()
            } catch (t: Throwable) {
                onError("Recording stopped: ${t.message}")
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        if (sessionId >= 0) ShizukuBridge.stopStream(sessionId)
        sessionId = -1
        thread?.interrupt()
        thread = null
    }

    private fun readRawRanges(device: String) {
        val info = ShizukuBridge.exec("getevent -pl $device")
        Regex("ABS_MT_POSITION_X\\s*:.*?max\\s+(\\d+)").find(info)?.let {
            rawMaxX = it.groupValues[1].toIntOrNull() ?: 0
        }
        Regex("ABS_MT_POSITION_Y\\s*:.*?max\\s+(\\d+)").find(info)?.let {
            rawMaxY = it.groupValues[1].toIntOrNull() ?: 0
        }
    }

    private fun toScreenX(raw: Int) = if (rawMaxX > 0) (raw.toFloat() / rawMaxX * screenWidthPx).roundToInt() else raw
    private fun toScreenY(raw: Int) = if (rawMaxY > 0) (raw.toFloat() / rawMaxY * screenHeightPx).roundToInt() else raw

    /** Last whitespace-separated token of a getevent line, or null. */
    private fun lastToken(line: String): String? {
        val trimmed = line.trimEnd()
        val idx = trimmed.lastIndexOfAny(charArrayOf(' ', '\t'))
        if (idx < 0 || idx == trimmed.length - 1) return null
        return trimmed.substring(idx + 1)
    }

    private fun onDown(nowMs: Long) {
        if (touching) return
        touching = true
        downTime = nowMs
        downX = curX
        downY = curY
    }

    private fun onUp(nowMs: Long) {
        if (!touching) return
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

    private fun pollLoop() {
        var carry = ""
        while (running) {
            val chunk = ShizukuBridge.readStream(sessionId)
            if (chunk.isEmpty()) {
                try { Thread.sleep(40) } catch (_: InterruptedException) { break }
                continue
            }
            val text = carry + chunk
            val lines = text.split("\n")
            carry = if (!text.endsWith("\n")) lines.last() else ""
            val completeLines = if (!text.endsWith("\n")) lines.dropLast(1) else lines

            for (l in completeLines) {
                if (l.isBlank()) continue
                val nowMs = System.currentTimeMillis()
                when {
                    l.contains("ABS_MT_POSITION_X") ->
                        lastToken(l)?.toIntOrNull(16)?.let { curX = toScreenX(it) }
                    l.contains("ABS_MT_POSITION_Y") ->
                        lastToken(l)?.toIntOrNull(16)?.let { curY = toScreenY(it) }
                    l.contains("ABS_MT_TRACKING_ID") -> {
                        if (lastToken(l) == "ffffffff") onUp(nowMs) else onDown(nowMs)
                    }
                    l.contains("BTN_TOUCH") && l.contains("DOWN") -> onDown(nowMs)
                    l.contains("BTN_TOUCH") && l.contains("UP") -> onUp(nowMs)
                }
            }
            try { Thread.sleep(20) } catch (_: InterruptedException) { break }
        }
    }
}
