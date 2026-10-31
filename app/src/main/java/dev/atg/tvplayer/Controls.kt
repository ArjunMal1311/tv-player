package dev.atg.tvplayer

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

// Bottom bar shown on any remote key: play/pause state, time, progress. Hides after HIDE_MS.
class Controls(context: Context) : LinearLayout(context) {
    private val label = TextView(context).apply { setTextColor(Color.WHITE); textSize = 22f }
    private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
    private val main = Handler(Looper.getMainLooper())
    private val hide = Runnable { visibility = View.GONE }

    init {
        orientation = VERTICAL
        setBackgroundColor(0xB0000000.toInt())
        setPadding(64, 24, 64, 40)
        addView(label)
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, 24))
        layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        visibility = View.GONE
    }

    val showing get() = visibility == View.VISIBLE

    fun show() {
        visibility = View.VISIBLE
        main.removeCallbacks(hide)
        main.postDelayed(hide, HIDE_MS)
    }

    fun update(paused: Boolean, posMs: Long, durMs: Long) {
        label.text = "${if (paused) "❚❚  Paused" else "▶  Playing"}     ${fmt(posMs)} / ${if (durMs > 0) fmt(durMs) else "live"}"
        bar.progress = if (durMs > 0) (posMs * 1000 / durMs).toInt() else 0
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    companion object { const val HIDE_MS = 4000L }
}
