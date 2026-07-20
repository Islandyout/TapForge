package com.islandyout.tapforge

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private var status: TextView? = null
    private var shizukuStatus: TextView? = null
    private val d get() = resources.displayMetrics.density

    private val shizukuPermListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        runOnUiThread { refreshShizukuStatus() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(0xFF0E1B14.toInt())
        val p = (24 * d).toInt()
        col.setPadding(p, (48 * d).toInt(), p, p)

        val title = TextView(this)
        title.text = "TapForge"
        title.setTextColor(0xFF38E07B.toInt())
        title.textSize = 34f
        title.typeface = Typeface.DEFAULT_BOLD
        col.addView(title)

        val sub = TextView(this)
        sub.text = "Auto tap · long-press · swipe. Works over any app or game."
        sub.setTextColor(0xFF9BD8B4.toInt())
        sub.textSize = 15f
        sub.setPadding(0, (4 * d).toInt(), 0, (24 * d).toInt())
        col.addView(sub)

        val st = TextView(this)
        st.textSize = 15f
        st.setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
        val stBg = GradientDrawable()
        stBg.setColor(0xFF162B20.toInt())
        stBg.cornerRadius = 14 * d
        st.background = stBg
        col.addView(st)
        status = st

        fun button(text: String, filled: Boolean, onTap: () -> Unit): TextView {
            val b = TextView(this)
            b.text = text
            b.textSize = 16f
            b.typeface = Typeface.DEFAULT_BOLD
            b.gravity = Gravity.CENTER
            b.setTextColor(if (filled) 0xFF0E1B14.toInt() else 0xFF38E07B.toInt())
            val bg = GradientDrawable()
            bg.cornerRadius = 26 * d
            if (filled) bg.setColor(0xFF38E07B.toInt())
            else { bg.setColor(Color.TRANSPARENT); bg.setStroke((2 * d).toInt(), 0xFF38E07B.toInt()) }
            b.background = bg
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (54 * d).toInt())
            lp.topMargin = (14 * d).toInt()
            b.layoutParams = lp
            b.setOnClickListener {
                try { onTap() } catch (t: Throwable) {
                    st.text = "Error: " + t.message
                    st.setTextColor(0xFFE05B5B.toInt())
                }
            }
            return b
        }

        col.addView(button("1 · Enable accessibility service", true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(button("2 · Show / hide floating controller", false) {
            val svc = ClickerService.instance
            if (svc == null) {
                st.text = "Service not running yet — do step 1 first (find TapForge in the list, switch it on)."
                st.setTextColor(0xFFE0C43B.toInt())
            } else svc.toggleOverlay()
        })

        val shSt = TextView(this)
        shSt.textSize = 15f
        shSt.setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
        val shBg = GradientDrawable()
        shBg.setColor(0xFF162B20.toInt())
        shBg.cornerRadius = 14 * d
        shSt.background = shBg
        val shLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        shLp.topMargin = (16 * d).toInt()
        shSt.layoutParams = shLp
        col.addView(shSt)
        shizukuStatus = shSt

        col.addView(button("3 · Grant Shizuku permission (optional)", false) {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "Shizuku isn't running — open the Shizuku app and start it first.", Toast.LENGTH_LONG).show()
                return@button
            }
            if (Shizuku.isPreV11()) {
                Toast.makeText(this, "Installed Shizuku version is too old.", Toast.LENGTH_LONG).show()
                return@button
            }
            ShizukuBridge.requestPermission()
        })

        col.addView(button("4 · Capture raw events (tap 3x after pressing)", false) {
            shSt.text = "Capturing 5s \u2014 TAP THE SCREEN a few times NOW\u2026"
            shSt.setTextColor(0xFFE0C43B.toInt())
            shSt.textSize = 12f
            Thread {
                val dev = ShizukuBridge.findTouchDevice() ?: "/dev/input/event5"
                val ranges = ShizukuBridge.exec("getevent -pl $dev")
                val sid = ShizukuBridge.startStream("getevent -lt $dev")
                val sb = StringBuilder()
                if (sid >= 0) {
                    val end = System.currentTimeMillis() + 5000
                    while (System.currentTimeMillis() < end) {
                        sb.append(ShizukuBridge.readStream(sid))
                        try { Thread.sleep(100) } catch (_: InterruptedException) {}
                    }
                    ShizukuBridge.stopStream(sid)
                }
                val cap = sb.toString()
                // pull the X/Y max lines out of ranges for the report
                val mx = Regex("ABS_MT_POSITION_X.*?max\\s+(\\d+)").find(ranges)?.groupValues?.get(1) ?: "?"
                val my = Regex("ABS_MT_POSITION_Y.*?max\\s+(\\d+)").find(ranges)?.groupValues?.get(1) ?: "?"
                val wpx = resources.displayMetrics.widthPixels
                val hpx = resources.displayMetrics.heightPixels
                // keep only position / btn / tracking lines to stay readable
                val filtered = cap.lineSequence().filter {
                    it.contains("ABS_MT_POSITION") || it.contains("BTN_TOUCH") ||
                    it.contains("TRACKING_ID") || it.contains("ABS_MT_SLOT")
                }.take(24).joinToString("\n")
                runOnUiThread {
                    shSt.text = "CAPTURE (send screenshot)\n" +
                        "dev=" + dev + "  screen=" + wpx + "x" + hpx + "\n" +
                        "rawMaxX=" + mx + " rawMaxY=" + my + "\n" +
                        "events captured: " + (cap.length) + " chars\n\n" +
                        (if (filtered.isBlank()) "(NO position/touch events seen \u2014 tap during capture!)" else filtered)
                    shSt.setTextColor(if (filtered.isBlank()) 0xFFE05B5B.toInt() else 0xFFCFE8D8.toInt())
                }
            }.apply { isDaemon = true; start() }
        })

        val help = TextView(this)
        help.text = "\nHow to use\n\n" +
            "▶  start / pause the loop\n" +
            "⏺  record real taps/swipes, ⏹ to stop and save\n" +
            "＋  add a tap target (drag it anywhere)\n" +
            "⇢  add a swipe (drag point A and point B)\n" +
            "⊕  add a long-press target\n" +
            "－  remove the last target\n" +
            "⚙  interval, durations, loops, anti-detection, save/load\n" +
            "✥  drag the controller bar itself\n\n" +
            "Targets fire in numbered order, then the loop repeats.\n\n" +
            "With Shizuku granted (step 3), recording no longer blocks the " +
            "screen — you can play normally while it records, and playback " +
            "uses the same shell injection. Without it, recording uses a " +
            "screen overlay instead."
        help.setTextColor(0xFFCFE8D8.toInt())
        help.textSize = 14f
        help.setPadding(0, (10 * d).toInt(), 0, 0)
        col.addView(help)

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onStart() {
        super.onStart()
        Shizuku.addRequestPermissionResultListener(shizukuPermListener)
    }

    override fun onStop() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermListener)
        super.onStop()
    }

    private fun refreshShizukuStatus() {
        val sh = shizukuStatus ?: return
        val available = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        when {
            !available -> {
                sh.text = "Shizuku:  NOT RUNNING — install the Shizuku app and start it (see setup guide), then come back."
                sh.setTextColor(0xFFE0C43B.toInt())
            }
            ShizukuBridge.hasPermission() -> {
                sh.text = "Shizuku:  GRANTED ✓ — recording and playback will use it automatically (no screen overlay needed)."
                sh.setTextColor(0xFF38E07B.toInt())
            }
            else -> {
                sh.text = "Shizuku:  running, permission not granted yet — tap step 3."
                sh.setTextColor(0xFFE0C43B.toInt())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val on = ClickerService.instance != null
        status?.text = if (on) "Service status:  RUNNING ✓  — use button 2 to toggle the controller"
        else "Service status:  OFF — tap step 1, find TapForge, switch it on"
        status?.setTextColor(if (on) 0xFF38E07B.toInt() else 0xFFE05B5B.toInt())
        refreshShizukuStatus()
    }
}
