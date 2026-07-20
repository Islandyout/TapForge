package com.islandyout.tapforge

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Thin wrapper around Shizuku: permission handling + running shell commands
 * through Shizuku's privileged process, without requiring root.
 *
 * Playback uses `input tap` / `input swipe` via shell — more reliable and
 * less detectable than AccessibilityService.dispatchGesture().
 *
 * Recording reads raw touch events from /dev/input/eventX via `getevent`,
 * which works without any on-screen overlay, so the app underneath is
 * fully visible and responsive while you play/record.
 */
object ShizukuBridge {

    const val REQUEST_CODE = 7341

    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun hasPermission(): Boolean {
        if (!isAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    /** Call from an Activity. Result arrives via Shizuku.OnRequestPermissionResultListener. */
    fun requestPermission() {
        if (!isAvailable()) return
        try {
            if (Shizuku.isPreV11()) return // unsupported ancient version
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (_: Throwable) {
        }
    }

    /**
     * Runs a shell command through Shizuku's privileged process and returns
     * combined stdout. Blocking — call from a background thread.
     */
    fun exec(cmd: String): String {
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val out = BufferedReader(InputStreamReader(process.inputStream)).readText()
            process.waitFor()
            out
        } catch (t: Throwable) {
            "ERR: ${t.message}"
        }
    }

    fun tap(x: Int, y: Int) {
        exec("input tap $x $y")
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) {
        exec("input swipe $x1 $y1 $x2 $y2 $durationMs")
    }

    /** Long-press is just a zero-distance swipe held for durationMs. */
    fun hold(x: Int, y: Int, durationMs: Long) {
        exec("input swipe $x $y $x $y $durationMs")
    }

    /**
     * Finds the touchscreen's /dev/input/eventX path by scanning `getevent -i`
     * output for a device with ABS_MT_POSITION_X capability. Device paths vary
     * by phone, so this is detected at runtime rather than hardcoded.
     */
    fun findTouchDevice(): String? {
        val listing = exec("getevent -i")
        var currentDevice: String? = null
        listing.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.startsWith("add device") && t.contains(":")) {
                currentDevice = t.substringAfterLast(":").trim()
            }
            if (t.contains("ABS_MT_POSITION_X") && currentDevice != null) {
                return currentDevice
            }
        }
        return null
    }
}
