package com.islandyout.tapforge

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
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private val d get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E1B14.toInt())
            val p = (24 * d).toInt(); setPadding(p, (48 * d).toInt(), p, p)
        }

        col.addView(TextView(this).apply {
            text = "TapForge"
            setTextColor(0xFF38E07B.toInt()); textSize = 34f; typeface = Typeface.DEFAULT_BOLD
        })
        col.addView(TextView(this).apply {
            text = "Auto tap · long-press · swipe. Works over any app or game."
            setTextColor(0xFF9BD8B4.toInt()); textSize = 15f
            setPadding(0, (4 * d).toInt(), 0, (24 * d).toInt())
        })

        status = TextView(this).apply {
            textSize = 15f; setPadding((16 * d).toInt(), (14 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
            background = GradientDrawable().apply { setColor(0xFF162B20.toInt()); cornerRadius = 14 * d }
        }
        col.addView(status)

        fun button(text: String, filled: Boolean, onTap: () -> Unit) = TextView(this).apply {
            this.text = text; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setTextColor(if (filled) 0xFF0E1B14.toInt() else 0xFF38E07B.toInt())
            background = GradientDrawable().apply {
                cornerRadius = 26 * d
                if (filled) setColor(0xFF38E07B.toInt())
                else { setColor(Color.TRANSPARENT); setStroke((2 * d).toInt(), 0xFF38E07B.toInt()) }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (54 * d).toInt())
            lp.topMargin = (14 * d).toInt(); layoutParams = lp
            setOnClickListener { onTap() }
        }

        col.addView(button("1 · Enable accessibility service", true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(button("2 · Show / hide floating controller", false) {
            val svc = ClickerService.instance
            if (svc == null) {
                status.text = "Service not running yet — enable it in step 1 first (find \"TapForge\" in the list and switch it on)."
                status.setTextColor(0xFFE0C43B.toInt())
            } else svc.toggleOverlay()
        })

        col.addView(TextView(this).apply {
            text = "\nHow to use\n\n" +
                "▶  start / pause the loop\n" +
                "＋  add a tap target (drag it anywhere)\n" +
                "⇢  add a swipe (drag point A and point B)\n" +
                "⊕  add a long-press target\n" +
                "－  remove the last target\n" +
                "⚙  interval, durations, loop count, anti-detection jitter, save/load script\n" +
                "✥  drag the controller bar itself\n\n" +
                "Targets fire in numbered order, then the loop repeats. " +
                "Anti-detection adds small random timing and position offsets to every action so it doesn't look robotic.\n\n" +
                "Note: some online games ban automation in their terms of service — use judgement, especially in competitive multiplayer."
            setTextColor(0xFFCFE8D8.toInt()); textSize = 14f
            setPadding(0, (10 * d).toInt(), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        val on = ClickerService.instance != null
        status.text = if (on) "Service status:  RUNNING ✓  — use button 2 to toggle the controller"
        else "Service status:  OFF — tap step 1, find TapForge in the accessibility list, and switch it on"
        status.setTextColor(if (on) 0xFF38E07B.toInt() else 0xFFE05B5B.toInt())
    }
}
