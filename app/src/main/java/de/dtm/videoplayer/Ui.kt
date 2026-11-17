package de.dtm.videoplayer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

object Pal {
    val BG = Color.parseColor("#0A0C12")
    val SURFACE = Color.parseColor("#151824")
    val SURFACE2 = Color.parseColor("#1E2233")
    val ACCENT = Color.parseColor("#7C5CFF")
    val ACCENT2 = Color.parseColor("#00D4FF")
    val TEXT = Color.parseColor("#F2F4FA")
    val MUTED = Color.parseColor("#8B93A7")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun roundRect(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(color)
        if (strokePx > 0) setStroke(strokePx, strokeColor)
    }

fun withRipple(base: Drawable, rippleColor: Int = 0x33FFFFFF): Drawable =
    RippleDrawable(ColorStateList.valueOf(rippleColor), base, null)

/** Runde „Pille" als Button. filled = Akzentfarbe, sonst Umriss. */
fun Context.pill(text: String, filled: Boolean, onClick: () -> Unit): TextView =
    TextView(this).apply {
        this.text = text
        setTextColor(Pal.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setPadding(dp(20), dp(13), dp(20), dp(13))
        val bg = if (filled) {
            GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Pal.ACCENT, Pal.ACCENT2)
            ).apply { cornerRadius = dp(28).toFloat() }
        } else {
            roundRect(Pal.SURFACE, dp(28).toFloat(), Pal.SURFACE2, dp(1))
        }
        background = withRipple(bg)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

fun Context.iconBtn(res: Int, sizeDp: Int = 44, padDp: Int = 10, onClick: () -> Unit): ImageButton =
    ImageButton(this).apply {
        setImageResource(res)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        background = RippleDrawable(
            ColorStateList.valueOf(0x44FFFFFF),
            null,
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        )
        setOnClickListener { onClick() }
    }

fun Context.label(text: String, sp: Float = 14f, color: Int = Pal.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

fun Context.slider(max: Int, progress: Int, onChange: (Int, Boolean) -> Unit): SeekBar =
    SeekBar(this).apply {
        this.max = max
        this.progress = progress
        progressTintList = ColorStateList.valueOf(Pal.ACCENT)
        thumbTintList = ColorStateList.valueOf(Pal.ACCENT2)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = onChange(p, fromUser)
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

fun matchWrap(topDp: Int = 0, ctx: Context? = null): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        if (ctx != null) topMargin = ctx.dp(topDp)
    }

fun View.fadeTo(visible: Boolean, durationMs: Long = 180L) {
    animate().cancel()
    if (visible) {
        visibility = View.VISIBLE
        animate().alpha(1f).setDuration(durationMs).start()
    } else {
        animate().alpha(0f).setDuration(durationMs).withEndAction { visibility = View.GONE }.start()
    }
}
