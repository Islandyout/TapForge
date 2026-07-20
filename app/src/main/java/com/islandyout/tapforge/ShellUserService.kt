package com.islandyout.tapforge

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs inside the process Shizuku spawns with shell (or root) privilege.
 * This replaces the old Shizuku.newProcess() helper, which was made
 * private/internal in newer Shizuku-API versions — UserService is now the
 * only supported way to execute privileged commands.
 *
 * Must have a no-arg constructor (or one taking Context, per Shizuku v13+);
 * we don't need Context here so the plain constructor is used.
 */
class ShellUserService : IShellService.Stub() {

    private val nextId = AtomicInteger(1)
    private val streams = ConcurrentHashMap<Int, Process>()
    private val buffers = ConcurrentHashMap<Int, StringBuilder>()

    override fun exec(cmd: String): String {
        return try {
            val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
            p.waitFor()
            out
        } catch (t: Throwable) {
            "ERR: ${t.message}"
        }
    }

    override fun startStream(cmd: String): Int {
        val id = nextId.getAndIncrement()
        return try {
            val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            streams[id] = p
            val sb = StringBuilder()
            buffers[id] = sb
            Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(p.inputStream))
                    var line: String?
                    while (true) {
                        line = reader.readLine()
                        if (line == null) break
                        synchronized(sb) { sb.append(line).append('\n') }
                    }
                } catch (_: Throwable) {
                }
            }.apply { isDaemon = true; start() }
            id
        } catch (t: Throwable) {
            -1
        }
    }

    override fun readStream(sessionId: Int): String {
        val sb = buffers[sessionId] ?: return ""
        return synchronized(sb) {
            val s = sb.toString()
            sb.setLength(0)
            s
        }
    }

    override fun stopStream(sessionId: Int) {
        streams.remove(sessionId)?.let { try { it.destroy() } catch (_: Exception) {} }
        buffers.remove(sessionId)
    }

    override fun destroy() {
        streams.values.forEach { try { it.destroy() } catch (_: Exception) {} }
        streams.clear()
        buffers.clear()
        // UserService processes are killed by Shizuku on unbind; nothing else to do.
    }
}
