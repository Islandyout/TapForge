package com.islandyout.tapforge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.random.Random

class ClickerService : AccessibilityService() {

    companion object {
        var instance: ClickerService? = null
            private set
        const val ACCENT = 0xFF38E07B.toInt()
        const val SWIPE_ACCENT = 0xFF3BB8E0.toInt()
        const val DARK = 0xEE10241A.toInt()
    }

    private lateinit var wm: WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("tapforge", Context.MODE_PRIVATE) }

    // ---- config ----
    private var intervalMs = 200L        // delay between actions
    private var tapDurationMs = 30L
    private var swipeDurationMs = 300L
    private var holdDurationMs = 600L
    private var repeatCount = 0L         // 0 = infinite loops
    private var jitterTimeMs = 25L       // anti-detection: random +/- ms
    private var jitterPosPx = 4          // anti-detection: random +/- px

    // ---- state ----
    private var running = false
    private var actionIndex = 0
    private var loopsDone = 0L
    private var controllerView: View? = null
    private var panelView: View? = null
    private val targets = mutableListOf<Target>()
    private var density = 1f

    // =========================================================
    // Target = one draggable overlay marker (tap / hold / swipe)
    // =========================================================
    inner class Target(val type: String) { // "tap" | "hold" | "swipe"
        var v1: View = makeMarker(targets.size + 1, if (type == "swipe") SWIPE_ACCENT else ACCENT, type == "hold")
        var v2: View? = if (type == "swipe") makeMarker(targets.size + 1, SWIPE_ACCENT, false, "B") else null
        val p1 = overlayParams()
        val p2 = if (type == "swipe") overlayParams() else null

        init {
            val cx = (140 * density).roundToInt() + (targets.size % 4) * (70 * density).roundToInt()
            val cy = (260 * density).roundToInt() + (targets.size / 4) * (80 * density).roundToInt()
            p1.x = cx; p1.y = cy
            p2?.let { it.x = cx + (110 * density).roundToInt(); it.y = cy + (110 * density).roundToInt() }
            attachDrag(v1, p1)
            wm.addView(v1, p1)
            v2?.let { second -> attachDrag(second, p2!!); wm.addView(second, p2) }
        }

        fun center(view: View): Pair<Float, Float> {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            return Pair(loc[0] + view.width / 2f, loc[1] + view.height / 2f)
        }

        fun setRunMode(run: Boolean) {
            fun apply(v: View, p: WindowManager.LayoutParams) {
                if (run) p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                else p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                v.alpha = if (run) 0.35f else 1f
                try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
            }
            apply(v1, p1)
            v2?.let { apply(it, p2!!) }
        }

        fun remove() {
            try { wm.removeView(v1) } catch (_: Exception) {}
            v2?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        }

        fun relabel(n: Int) {
            ((v1 as FrameLayout).getChildAt(0) as TextView).text = n.toString()
            (v2 as? FrameLayout)?.let { (it.getChildAt(0) as TextView).text = "${n}B" }
        }
    }

    private fun makeMarker(num: Int, color: Int, square: Boolean, suffix: String = ""): View {
        val size = (52 * density).roundToInt()
        val ring = object : FrameLayout(this) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; style = Paint.Style.STROKE; strokeWidth = 4 * density
            }
            val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; strokeWidth = 3 * density; strokeCap = Paint.Cap.ROUND
            }
            override fun dispatchDraw(canvas: android.graphics.Canvas) {
                val c = width / 2f
                val r = width / 2f - 3 * density
                if (square) {
                    val d = r * 0.85f
                    canvas.drawRoundRect(c - d, c - d, c + d, c + d, 8 * density, 8 * density, paint)
                } else canvas.drawCircle(c, c, r, paint)
                val t = 7 * density
                canvas.drawLine(c, c - r, c, c - r + t, cross)
                canvas.drawLine(c, c + r, c, c + r - t, cross)
                canvas.drawLine(c - r, c, c - r + t, c, cross)
                canvas.drawLine(c + r, c, c + r - t, c, cross)
                super.dispatchDraw(canvas)
            }
        }
        ring.setWillNotDraw(false)
        val label = TextView(this).apply {
            text = if (suffix.isEmpty()) num.toString() else "$num$suffix"
            setTextColor(color)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            val bg = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99000000.toInt()) }
            background = bg
        }
        ring.addView(label, FrameLayout.LayoutParams(size, size))
