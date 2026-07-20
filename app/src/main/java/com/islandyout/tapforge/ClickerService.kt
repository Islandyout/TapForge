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

    // ---- recording ----
    private var recording = false
    private var recordOverlay: View? = null
    private lateinit var recordBtn: TextView
    private data class RecEvent(
        val type: String,      // "tap" | "hold" | "swipe"
        val x1: Float, val y1: Float,
        val x2: Float = 0f, val y2: Float = 0f,
        val delayMs: Long,     // gap since previous event started
        val durationMs: Long   // hold/swipe duration; ignored for tap
    )
    private val recordedEvents = mutableListOf<RecEvent>()
    private var recStartTime = 0L
    private var recLastEventStart = 0L
    // in-progress touch tracking
    private var touchDownTime = 0L
    private var touchDownX = 0f
    private var touchDownY = 0f
    private val tapMoveSlopPx get() = 18 * density
    private val holdThresholdMs = 450L

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
        return ring.apply { layoutParams = FrameLayout.LayoutParams(size, size) }
    }

    private fun overlayParams() = WindowManager.LayoutParams(
        (52 * density).roundToInt(), (52 * density).roundToInt(),
        overlayType(),
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= 22) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(v: View, p: WindowManager.LayoutParams) {
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = p.x; sy = p.y; tx = e.rawX; ty = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    p.x = sx + (e.rawX - tx).roundToInt()
                    p.y = sy + (e.rawY - ty).roundToInt()
                    try { wm.updateViewLayout(view, p) } catch (_: Exception) {}
                    true
                }
                else -> true
            }
        }
    }

    // =========================================================
    // Controller bar
    // =========================================================
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        density = resources.displayMetrics.density
        loadSettings()
        showController()
        Toast.makeText(this, "TapForge ready — drag the bar, add targets, press ▶", Toast.LENGTH_LONG).show()
    }

    private fun pill(text: String, color: Int = ACCENT, onTap: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            textSize = 19f
            gravity = Gravity.CENTER
            val s = (44 * density).roundToInt()
            layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { onTap() }
        }

    private lateinit var playBtn: TextView

    @SuppressLint("ClickableViewAccessibility")
    private fun showController() {
        if (controllerView != null) return
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(DARK); cornerRadius = 26 * density
                setStroke((1.5f * density).roundToInt(), 0x5538E07B)
            }
            background = bg
            val pad = (4 * density).roundToInt()
            setPadding(pad, pad, pad, pad)
        }

        playBtn = pill("▶") { toggleRun() }
        recordBtn = pill("⏺", 0xFFE05B5B.toInt()) { toggleRecording() }
        val drag = pill("✥", 0xFF9BD8B4.toInt()) {}

        col.addView(playBtn)
        col.addView(recordBtn)
        col.addView(pill("＋") { addTarget("tap") })
        col.addView(pill("⇢", SWIPE_ACCENT) { addTarget("swipe") })
        col.addView(pill("⊕", 0xFFE0C43B.toInt()) { addTarget("hold") })
        col.addView(pill("－", 0xFFE05B5B.toInt()) { removeLastTarget() })
        col.addView(pill("⚙", 0xFFDDDDDD.toInt()) { togglePanel() })
        col.addView(drag)

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (10 * density).roundToInt(); y = (200 * density).roundToInt() }

        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        drag.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = p.x; sy = p.y; tx = e.rawX; ty = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    p.x = sx + (e.rawX - tx).roundToInt(); p.y = sy + (e.rawY - ty).roundToInt()
                    try { wm.updateViewLayout(col, p) } catch (_: Exception) {}
                    true
                }
                else -> true
            }
        }

        wm.addView(col, p)
        controllerView = col
    }

    fun toggleOverlay() {
        handler.post {
            if (controllerView == null) showController()
            else {
                stopRun()
                targets.forEach { it.remove() }; targets.clear()
                panelView?.let { try { wm.removeView(it) } catch (_: Exception) {} }; panelView = null
                try { wm.removeView(controllerView) } catch (_: Exception) {}
                controllerView = null
            }
        }
    }

    private fun addTarget(type: String) {
        if (running) return
        targets.add(Target(type))
    }

    private fun removeLastTarget() {
        if (running) return
        targets.removeLastOrNull()?.remove()
    }

    // =========================================================
    // Engine
    // =========================================================
    private fun toggleRun() = if (running) stopRun() else startRun()

    private fun startRun() {
        if (targets.isEmpty()) {
            Toast.makeText(this, "Add at least one target (＋ / ⇢ / ⊕)", Toast.LENGTH_SHORT).show()
            return
        }
        running = true
        actionIndex = 0
        loopsDone = 0
        targets.forEach { it.setRunMode(true) }
        playBtn.text = "⏸"
        playBtn.setTextColor(0xFFE05B5B.toInt())
        panelView?.let { try { wm.removeView(it) } catch (_: Exception) {} }; panelView = null
        handler.post(loop)
    }

    private fun stopRun() {
        running = false
        handler.removeCallbacks(loop)
        targets.forEach { it.setRunMode(false) }
        if (::playBtn.isInitialized) { playBtn.text = "▶"; playBtn.setTextColor(ACCENT) }
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!running || targets.isEmpty()) return
            dispatchTarget(targets[actionIndex])
            actionIndex++
            if (actionIndex >= targets.size) {
                actionIndex = 0
                loopsDone++
                if (repeatCount > 0 && loopsDone >= repeatCount) {
                    handler.post { stopRun(); Toast.makeText(this@ClickerService, "TapForge: done ($loopsDone loops)", Toast.LENGTH_SHORT).show() }
                    return
                }
            }
            val jitter = if (jitterTimeMs > 0) Random.nextLong(-jitterTimeMs, jitterTimeMs + 1) else 0L
            handler.postDelayed(this, (intervalMs + jitter).coerceAtLeast(10))
        }
    }

    private fun jx(v: Float) = v + if (jitterPosPx > 0) Random.nextInt(-jitterPosPx, jitterPosPx + 1) else 0

    private fun dispatchTarget(t: Target) {
        val (x1, y1) = t.center(t.v1)
        val path = Path()
        val duration: Long
        when (t.type) {
            "swipe" -> {
                val (x2, y2) = t.center(t.v2!!)
                path.moveTo(jx(x1), jx(y1)); path.lineTo(jx(x2), jx(y2))
                duration = swipeDurationMs
            }
            "hold" -> { path.moveTo(jx(x1), jx(y1)); duration = holdDurationMs }
            else -> { path.moveTo(jx(x1), jx(y1)); duration = tapDurationMs }
        }
        try {
            val stroke = GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1))
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (_: Exception) {}
    }

    // =========================================================
    // Recording
    // =========================================================
    private fun toggleRecording() = if (recording) stopRecording() else startRecording()

    @SuppressLint("ClickableViewAccessibility")
    private fun startRecording() {
        if (running) { Toast.makeText(this, "Stop playback first", Toast.LENGTH_SHORT).show(); return }
        stopRun()
        recordedEvents.clear()
        recStartTime = System.currentTimeMillis()
        recLastEventStart = recStartTime

        val overlay = View(this)
        overlay.setBackgroundColor(0x18E05B5B) // faint red tint so it's obvious recording is live
        overlay.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownTime = System.currentTimeMillis()
                    touchDownX = e.rawX; touchDownY = e.rawY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val now = System.currentTimeMillis()
                    val dur = now - touchDownTime
                    val dx = e.rawX - touchDownX
                    val dy = e.rawY - touchDownY
                    val dist = kotlin.math.hypot(dx, dy)
                    val delay = touchDownTime - recLastEventStart
                    recLastEventStart = touchDownTime

                    val ev = if (dist > tapMoveSlopPx) {
                        RecEvent("swipe", touchDownX, touchDownY, e.rawX, e.rawY, delay.coerceAtLeast(0), dur.coerceAtLeast(20))
                    } else if (dur >= holdThresholdMs) {
                        RecEvent("hold", touchDownX, touchDownY, delayMs = delay.coerceAtLeast(0), durationMs = dur)
                    } else {
                        RecEvent("tap", touchDownX, touchDownY, delayMs = delay.coerceAtLeast(0), durationMs = 30)
                    }
                    recordedEvents.add(ev)
                    handler.post {
                        Toast.makeText(this, "${recordedEvents.size} · ${ev.type}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            true
        }

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        wm.addView(overlay, p)
        recordOverlay = overlay

        // Re-add the controller on top of the record overlay so ⏹ (and every
        // other button) stays reachable while recording is active. Without
        // this the full-screen overlay sits above the controller and eats
        // all touches, including the stop button — locking the screen.
        controllerView?.let { cv ->
            try { wm.removeView(cv) } catch (_: Exception) {}
            try { wm.addView(cv, (cv.layoutParams as WindowManager.LayoutParams)) } catch (_: Exception) {}
        }

        recording = true
        recordBtn.text = "⏹"
        recordBtn.setTextColor(ACCENT)
        Toast.makeText(this, "Recording — tap/hold/swipe on screen. Press ⏹ when done.", Toast.LENGTH_LONG).show()

        // Safety valve: never let recording (and the blocking overlay) run
        // forever if something goes wrong — auto-stop after 2 minutes.
        handler.postDelayed(recordingSafetyStop, 120_000L)
    }

    private val recordingSafetyStop = Runnable {
        if (recording) {
            Toast.makeText(this, "Recording auto-stopped after 2 min", Toast.LENGTH_LONG).show()
            stopRecording()
        }
    }

    private fun stopRecording() {
        recording = false
        handler.removeCallbacks(recordingSafetyStop)
        recordOverlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        recordOverlay = null
        recordBtn.text = "⏺"
        recordBtn.setTextColor(0xFFE05B5B.toInt())

        if (recordedEvents.isEmpty()) {
            Toast.makeText(this, "No actions recorded", Toast.LENGTH_SHORT).show()
            return
        }

        // Replace current targets with the recorded sequence.
        targets.forEach { it.remove() }; targets.clear()
        recordedEvents.forEachIndexed { i, ev ->
            val t = Target(ev.type)
            t.p1.x = ev.x1.roundToInt(); t.p1.y = ev.y1.roundToInt()
            try { wm.updateViewLayout(t.v1, t.p1) } catch (_: Exception) {}
            if (ev.type == "swipe") {
                t.p2!!.x = ev.x2.roundToInt(); t.p2.y = ev.y2.roundToInt()
                try { wm.updateViewLayout(t.v2!!, t.p2) } catch (_: Exception) {}
            }
            t.relabel(i + 1)
            targets.add(t)
        }
        // Use the recorded timing: set global interval to the average gap,
        // and per-type durations to the last recorded values of that type.
        val avgDelay = recordedEvents.map { it.delayMs }.filter { it > 0 }.average()
        if (!avgDelay.isNaN()) intervalMs = avgDelay.toLong().coerceAtLeast(10)
        recordedEvents.lastOrNull { it.type == "swipe" }?.let { swipeDurationMs = it.durationMs }
        recordedEvents.lastOrNull { it.type == "hold" }?.let { holdDurationMs = it.durationMs }
        saveSettings()
        saveScript()
        Toast.makeText(this, "Recorded ${recordedEvents.size} actions — saved as script. Press ▶ to replay.", Toast.LENGTH_LONG).show()
    }

    // =========================================================
    // Settings + scripts panel
    // =========================================================
    private fun togglePanel() {
        panelView?.let { try { wm.removeView(it) } catch (_: Exception) {}; panelView = null; return }
        val ctx = this

        fun field(label: String, value: String): Pair<LinearLayout, EditText> {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(ctx).apply {
                text = label; setTextColor(0xFFCFE8D8.toInt()); textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val et = EditText(ctx).apply {
                setText(value); inputType = InputType.TYPE_CLASS_NUMBER
                setTextColor(Color.WHITE); textSize = 14f
                setBackgroundColor(0x33FFFFFF)
                minWidth = (86 * density).roundToInt(); gravity = Gravity.CENTER
            }
            row.addView(et)
            return Pair(row, et)
        }

        val (r1, fInterval) = field("Interval between actions (ms)", intervalMs.toString())
        val (r2, fSwipe) = field("Swipe duration (ms)", swipeDurationMs.toString())
        val (r3, fHold) = field("Long-press duration (ms)", holdDurationMs.toString())
        val (r4, fRepeat) = field("Loops (0 = infinite)", repeatCount.toString())
        val (r5, fJt) = field("Anti-detect time jitter ± (ms)", jitterTimeMs.toString())
        val (r6, fJp) = field("Anti-detect position jitter ± (px)", jitterPosPx.toString())

        fun button(text: String, color: Int, onTap: () -> Unit) = TextView(ctx).apply {
            this.text = text; setTextColor(0xFF0E1B14.toInt()); textSize = 14f
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            val bg = GradientDrawable().apply { setColor(color); cornerRadius = 20 * density }
            background = bg
            val pv = (9 * density).roundToInt(); val ph = (14 * density).roundToInt()
            setPadding(ph, pv, ph, pv)
            setOnClickListener { onTap() }
        }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(DARK); cornerRadius = 18 * density
                setStroke((1.5f * density).roundToInt(), 0x5538E07B)
            }
            background = bg
            val pad = (16 * density).roundToInt(); setPadding(pad, pad, pad, pad)
        }
        col.addView(TextView(ctx).apply {
            text = "TapForge settings"; setTextColor(ACCENT); textSize = 17f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, (10 * density).roundToInt())
        })
        listOf(r1, r2, r3, r4, r5, r6).forEach {
            it.setPadding(0, (5 * density).roundToInt(), 0, (5 * density).roundToInt()); col.addView(it)
        }

        val btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (12 * density).roundToInt(), 0, 0) }
        fun space() = View(ctx).apply { layoutParams = LinearLayout.LayoutParams((8 * density).roundToInt(), 1) }

        btnRow.addView(button("Apply", ACCENT) {
            intervalMs = fInterval.text.toString().toLongOrNull()?.coerceAtLeast(10) ?: intervalMs
            swipeDurationMs = fSwipe.text.toString().toLongOrNull()?.coerceAtLeast(20) ?: swipeDurationMs
            holdDurationMs = fHold.text.toString().toLongOrNull()?.coerceAtLeast(100) ?: holdDurationMs
            repeatCount = fRepeat.text.toString().toLongOrNull() ?: repeatCount
            jitterTimeMs = fJt.text.toString().toLongOrNull()?.coerceAtLeast(0) ?: jitterTimeMs
            jitterPosPx = fJp.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: jitterPosPx
            saveSettings()
            togglePanel()
            Toast.makeText(ctx, "Applied", Toast.LENGTH_SHORT).show()
        })
        btnRow.addView(space())
        btnRow.addView(button("Save script", 0xFF9BD8B4.toInt()) { saveScript(); togglePanel() })
        btnRow.addView(space())
        btnRow.addView(button("Load script", 0xFF3BB8E0.toInt()) { loadScript(); togglePanel() })
        col.addView(btnRow)

        val scroll = ScrollView(ctx).apply { addView(col) }
        val p = WindowManager.LayoutParams(
            (320 * density).roundToInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(), 0, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER; softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN }
        wm.addView(scroll, p)
        panelView = scroll
    }

    // =========================================================
    // Script + settings persistence
    // =========================================================
    private fun saveSettings() {
        prefs.edit()
            .putLong("interval", intervalMs).putLong("swipe", swipeDurationMs)
            .putLong("hold", holdDurationMs).putLong("repeat", repeatCount)
            .putLong("jt", jitterTimeMs).putInt("jp", jitterPosPx)
            .apply()
    }

    private fun loadSettings() {
        intervalMs = prefs.getLong("interval", 200)
        swipeDurationMs = prefs.getLong("swipe", 300)
        holdDurationMs = prefs.getLong("hold", 600)
        repeatCount = prefs.getLong("repeat", 0)
        jitterTimeMs = prefs.getLong("jt", 25)
        jitterPosPx = prefs.getInt("jp", 4)
    }

    private fun saveScript() {
        val arr = JSONArray()
        targets.forEach { t ->
            val o = JSONObject()
            o.put("type", t.type)
            o.put("x1", t.p1.x); o.put("y1", t.p1.y)
            t.p2?.let { o.put("x2", it.x); o.put("y2", it.y) }
            arr.put(o)
        }
        prefs.edit().putString("script_default", arr.toString()).apply()
        Toast.makeText(this, "Script saved (${targets.size} actions)", Toast.LENGTH_SHORT).show()
    }

    private fun loadScript() {
        val raw = prefs.getString("script_default", null)
        if (raw == null) { Toast.makeText(this, "No saved script yet", Toast.LENGTH_SHORT).show(); return }
        stopRun()
        targets.forEach { it.remove() }; targets.clear()
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val t = Target(o.getString("type"))
            t.p1.x = o.getInt("x1"); t.p1.y = o.getInt("y1")
            try { wm.updateViewLayout(t.v1, t.p1) } catch (_: Exception) {}
            if (t.type == "swipe" && o.has("x2")) {
                t.p2!!.x = o.getInt("x2"); t.p2.y = o.getInt("y2")
                try { wm.updateViewLayout(t.v2!!, t.p2) } catch (_: Exception) {}
            }
            t.relabel(i + 1)
            targets.add(t)
        }
        Toast.makeText(this, "Script loaded (${targets.size} actions)", Toast.LENGTH_SHORT).show()
    }

    // =========================================================
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopRun() }

    override fun onDestroy() {
        stopRun()
        handler.removeCallbacks(recordingSafetyStop)
        recordOverlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        targets.forEach { it.remove() }
        panelView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        controllerView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        instance = null
        super.onDestroy()
    }
}
