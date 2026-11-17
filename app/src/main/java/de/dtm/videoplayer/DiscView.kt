package de.dtm.videoplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * Start-Bildschirm-Animation: eine DVD schwebt über einem Laufwerk-Schlitz.
 * insert() schiebt sie in den Schlitz, danach läuft onInserted.
 */
class DiscView(ctx: Context) : View(ctx) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var discShader: Shader? = null
    private var inserting = false
    private var inserted = false
    private var insertStartNs = 0L
    private var progress = 0f
    private var onInserted: (() -> Unit)? = null

    private var rot = 0f
    private var t = 0f
    private var lastNs = 0L

    fun insert(done: () -> Unit) {
        if (inserting || inserted) return
        inserting = true
        insertStartNs = System.nanoTime()
        onInserted = done
    }

    fun reset() {
        inserting = false
        inserted = false
        progress = 0f
        onInserted = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        discShader = SweepGradient(
            0f, 0f,
            intArrayOf(
                Color.parseColor("#E9ECF4"), Color.parseColor("#B7A5FF"), Color.parseColor("#9FE9FF"),
                Color.parseColor("#F4F5FA"), Color.parseColor("#FFC6E3"), Color.parseColor("#C9D0E2"),
                Color.parseColor("#9FE9FF"), Color.parseColor("#E9ECF4")
            ),
            null
        )
    }

    private fun ease(x: Float): Float = x * x * (3f - 2f * x)

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0f else (now - lastNs) / 1e9f
        lastNs = now
        t += dt

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val r = min(w * 0.36f, h * 0.30f)

        // Laufwerk
        val bodyW = min(w * 0.92f, r * 3.1f)
        val bodyH = r * 0.62f
        val bodyTop = h * 0.76f
        val slotTop = bodyTop + bodyH * 0.28f
        val slotH = bodyH * 0.16f
        val slotCenter = slotTop + slotH / 2f

        if (inserting) {
            val p = ((now - insertStartNs) / 1e9f / 1.1f).coerceIn(0f, 1f)
            progress = ease(p)
            if (p >= 1f) {
                inserting = false
                inserted = true
                val cb = onInserted
                post { cb?.invoke() }
            }
        } else if (inserted) {
            progress = 1f
        }

        val idleY = bodyTop - r * 1.30f
        val endY = slotCenter + r
        val bob = sin(t * 2f) * r * 0.035f * (1f - progress)
        val cy = idleY + (endY - idleY) * progress + bob

        val speed = when {
            inserted -> 520f
            inserting -> 30f + progress * 490f
            else -> 28f
        }
        rot = (rot + speed * dt) % 360f

        // Glow
        paint.shader = RadialGradient(
            cx, idleY + r * 0.2f, r * 2.1f,
            intArrayOf(Color.parseColor("#447C5CFF"), Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // Laufwerk-Körper
        rect.set(cx - bodyW / 2f, bodyTop, cx + bodyW / 2f, bodyTop + bodyH)
        paint.shader = LinearGradient(
            0f, bodyTop, 0f, bodyTop + bodyH,
            Color.parseColor("#2C3246"), Color.parseColor("#141826"), Shader.TileMode.CLAMP
        )
        c.drawRoundRect(rect, bodyH * 0.28f, bodyH * 0.28f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.parseColor("#3C4560")
        c.drawRoundRect(rect, bodyH * 0.28f, bodyH * 0.28f, paint)
        paint.style = Paint.Style.FILL

        // Disc (am Schlitz abgeschnitten)
        c.save()
        c.clipRect(0f, 0f, w, slotCenter)
        c.translate(cx, cy)

        c.save()
        c.rotate(rot)
        paint.shader = discShader
        c.drawCircle(0f, 0f, r, paint)
        paint.shader = null
        // Rillen
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = Color.argb(40, 0, 0, 0)
        var rr = r * 0.40f
        while (rr < r * 0.97f) {
            c.drawCircle(0f, 0f, rr, paint)
            rr += r * 0.07f
        }
        paint.style = Paint.Style.FILL
        // Nabe
        paint.color = Color.parseColor("#D7DBE8")
        c.drawCircle(0f, 0f, r * 0.30f, paint)
        paint.color = Color.parseColor("#8E96AE")
        c.drawCircle(0f, 0f, r * 0.24f, paint)
        paint.color = Pal.BG
        c.drawCircle(0f, 0f, r * 0.09f, paint)
        paint.color = Pal.TEXT
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = r * 0.085f
        c.drawText("DTM  DVD", 0f, -r * 0.14f, paint)
        c.restore()

        // fester Glanz
        paint.shader = LinearGradient(
            -r, -r, r, r,
            Color.argb(90, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP
        )
        c.drawCircle(0f, 0f, r, paint)
        paint.shader = null
        c.restore()

        // Schlitz + LED (über der Disc)
        paint.color = Color.parseColor("#05060A")
        rect.set(cx - bodyW * 0.42f, slotTop, cx + bodyW * 0.42f, slotTop + slotH)
        c.drawRoundRect(rect, slotH / 2f, slotH / 2f, paint)

        val busy = inserting || inserted
        val blink = if (busy) (sin(t * 14f) > 0f) else (sin(t * 2.5f) > 0.6f)
        paint.color = when {
            busy && blink -> Color.parseColor("#3DFF9A")
            busy -> Color.parseColor("#0F5A38")
            blink -> Color.parseColor("#7C5CFF")
            else -> Color.parseColor("#2A2F42")
        }
        c.drawCircle(cx + bodyW * 0.42f - bodyH * 0.12f, bodyTop + bodyH * 0.72f, bodyH * 0.06f, paint)

        paint.color = Pal.MUTED
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = bodyH * 0.17f
        c.drawText("DVD · VIDEO · ALLES", cx - bodyW * 0.42f, bodyTop + bodyH * 0.80f, paint)

        postInvalidateOnAnimation()
    }
}
