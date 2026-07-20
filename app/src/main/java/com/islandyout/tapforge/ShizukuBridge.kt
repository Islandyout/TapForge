package com.islandyout.tapforge

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper around Shizuku: permission handling + running shell commands
 * through a bound UserService running with shell privilege.
 *
 * Shizuku.newProcess() — the old direct way to run a shell command — was
 * made private/internal in newer Shizuku-API versions (the maintainers want
 * everyone using UserService instead: https://github.com/RikkaApps/Shizuku-API).
 * So instead we bind ShellUserService (see IShellService.aidl +
 * ShellUserService.kt), which runs inside Shizuku's privileged process and
 * exposes exec/startStream/readStream over Binder.
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

    @Volatile private var service: IShellService? = null
    @Volatile private var binding = false
    private val bindLatch = ThreadLocal<CountDownLatch?>()

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.islandyout.tapforge", ShellUserService::class.java.name)
    ).daemon(false).processNameSuffix("shell").debuggable(true).version(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder()) IShellService.Stub.asInterface(binder) else null
            binding = false
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

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
     * Ensures the privileged UserService is bound. Blocks the calling thread
     * (briefly) waiting for the bind to complete — call off the main thread.
     */
    private fun ensureService(): IShellService? {
        service?.let { if (it.asBinder().pingBinder()) return it }
        if (!hasPermission()) return null
        if (binding) {
            // Another thread is already binding; wait a moment for it.
            repeat(50) {
                if (!binding) return@repeat
                Thread.sleep(50)
            }
            return service
        }
        binding = true
        val latch = CountDownLatch(1)
        val waitingConn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                connection.onServiceConnected(name, binder)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                connection.onServiceDisconnected(name)
            }
        }
        try {
            Shizuku.bindUserService(userServiceArgs, waitingConn)
        } catch (_: Throwable) {
            binding = false
            return null
        }
        latch.await(5, TimeUnit.SECONDS)
        binding = false
        return service
    }

    /** Runs a shell command via the privileged UserService. Blocking — call from a background thread. */
    fun exec(cmd: String): String {
        val svc = ensureService() ?: return "ERR: Shizuku service not bound"
        return try {
            svc.exec(cmd)
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

    /** Starts a long-running privileged command (e.g. getevent), returns a session id, or -1 on failure. */
    fun startStream(cmd: String): Int {
        val svc = ensureService() ?: return -1
        return try { svc.startStream(cmd) } catch (_: Throwable) { -1 }
    }

    /** Reads and clears buffered output from a stream session. */
    fun readStream(sessionId: Int): String {
        val svc = service ?: return ""
        return try { svc.readStream(sessionId) } catch (_: Throwable) { "" }
    }

    fun stopStream(sessionId: Int) {
        val svc = service ?: return
        try { svc.stopStream(sessionId) } catch (_: Throwable) {}
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
