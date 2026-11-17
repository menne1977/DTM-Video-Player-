package de.dtm.videoplayer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Basis aller Start-Bilder. insert() startet die ca. 3 Sekunden lange Animation,
 * danach läuft der übergebene Callback (Player starten).
 */
abstract class IntroView(ctx: Context) : View(ctx) {

    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val rect = RectF()
    protected var t = 0f
    protected var dt = 0f
    protected var raw = 0f
    protected var inserting = false
    protected var inserted = false
    protected open val duration = 3f

    private var lastNs = 0L
    private var startNs = 0L
    private var onDone: (() -> Unit)? = null

    fun insert(done: () -> Unit) {
        if (inserting || inserted) return
        inserting = true
        startNs = System.nanoTime()
        onDone = done
    }

    fun reset() {
        inserting = false
        inserted = false
        raw = 0f
        onDone = null
    }

    protected fun ease(x: Float): Float = x * x * (3f - 2f * x)

    protected fun col(hex: String): Int = Color.parseColor(hex)

    protected fun cols(vararg hex: String): IntArray = IntArray(hex.size) { Color.parseColor(hex[it]) }

    protected fun glow(c: Canvas, cx: Float, cy: Float, radius: Float, hex: String) {
        paint.shader = RadialGradient(cx, cy, radius, intArrayOf(col(hex), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, radius, paint)
        paint.shader = null
    }

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        dt = if (lastNs == 0L) 0f else (now - lastNs) / 1e9f
        lastNs = now
        t += dt
        if (inserting) {
            raw = ((now - startNs) / 1e9f / duration).coerceIn(0f, 1f)
            if (raw >= 1f) {
                inserting = false
                inserted = true
                val cb = onDone
                post { cb?.invoke() }
            }
        } else if (inserted) {
            raw = 1f
        }
        drawIntro(c, width.toFloat(), height.toFloat())
        postInvalidateOnAnimation()
    }

    protected abstract fun drawIntro(c: Canvas, w: Float, h: Float)

    protected fun ledColor(): Int {
        val busy = inserting || inserted
        val blink = if (busy) (sin(t * 14f) > 0f) else (sin(t * 2.5f) > 0.6f)
        return when {
            busy && blink -> col("#3DFF9A")
            busy -> col("#0F5A38")
            blink -> col("#7C5CFF")
            else -> col("#2A2F42")
        }
    }
}

// ====================================================================== DVD im Laufwerk

class DiscView(ctx: Context) : IntroView(ctx) {

    private var discShader: Shader? = null
    private var rot = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        discShader = SweepGradient(
            0f, 0f,
            cols("#E9ECF4", "#B7A5FF", "#9FE9FF", "#F4F5FA", "#FFC6E3", "#C9D0E2", "#9FE9FF", "#E9ECF4"),
            null
        )
    }

    override fun drawIntro(c: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val r = min(w * 0.36f, h * 0.30f)

        val bodyW = min(w * 0.92f, r * 3.1f)
        val bodyH = r * 0.62f
        val bodyTop = h * 0.76f
        val slotTop = bodyTop + bodyH * 0.28f
        val slotH = bodyH * 0.16f
        val slotCenter = slotTop + slotH / 2f

        // 0..1: Disc rutscht ganz in den Schlitz (ca. 2,7 s)
        val p = ease(((raw - 0.10f) / 0.90f).coerceIn(0f, 1f))
        val sy = 1f - 0.28f * p
        val idleY = bodyTop - r * 1.30f
        val endY = slotCenter + r * 0.72f + 2f
        val cy = idleY + (endY - idleY) * p + sin(t * 2f) * r * 0.035f * (1f - p)

        val speed = if (inserted) 520f else if (inserting) 30f + p * 490f else 28f
        rot = (rot + speed * dt) % 360f

        glow(c, cx, idleY + r * 0.2f, r * 2.1f, "#447C5CFF")

        // Laufwerk
        rect.set(cx - bodyW / 2f, bodyTop, cx + bodyW / 2f, bodyTop + bodyH)
        paint.shader = LinearGradient(0f, bodyTop, 0f, bodyTop + bodyH, col("#2C3246"), col("#141826"), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, bodyH * 0.28f, bodyH * 0.28f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = col("#3C4560")
        c.drawRoundRect(rect, bodyH * 0.28f, bodyH * 0.28f, paint)
        paint.style = Paint.Style.FILL

        // Disc, am Schlitz abgeschnitten
        c.save()
        c.clipRect(0f, 0f, w, slotCenter)
        c.translate(cx, cy)
        c.scale(1f, sy)
        c.save()
        c.rotate(rot)
        paint.shader = discShader
        c.drawCircle(0f, 0f, r, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        paint.color = Color.argb(40, 0, 0, 0)
        var rr = r * 0.40f
        while (rr < r * 0.97f) {
            c.drawCircle(0f, 0f, rr, paint)
            rr += r * 0.07f
        }
        paint.style = Paint.Style.FILL
        paint.color = col("#D7DBE8")
        c.drawCircle(0f, 0f, r * 0.30f, paint)
        paint.color = col("#8E96AE")
        c.drawCircle(0f, 0f, r * 0.24f, paint)
        paint.color = Pal.BG
        c.drawCircle(0f, 0f, r * 0.09f, paint)
        paint.color = Pal.TEXT
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = r * 0.085f
        c.drawText("DTM  DVD", 0f, -r * 0.14f, paint)
        c.restore()
        paint.shader = LinearGradient(-r, -r, r, r, Color.argb(90, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawCircle(0f, 0f, r, paint)
        paint.shader = null
        c.restore()

        // Schlitz + LED
        paint.color = col("#05060A")
        rect.set(cx - bodyW * 0.42f, slotTop, cx + bodyW * 0.42f, slotTop + slotH)
        c.drawRoundRect(rect, slotH / 2f, slotH / 2f, paint)
        paint.color = ledColor()
        c.drawCircle(cx + bodyW * 0.42f - bodyH * 0.12f, bodyTop + bodyH * 0.72f, bodyH * 0.06f, paint)
        paint.color = Pal.MUTED
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = bodyH * 0.17f
        c.drawText("DVD · VIDEO · ALLES", cx - bodyW * 0.42f, bodyTop + bodyH * 0.80f, paint)
    }
}

// ====================================================================== Kassette im Deck

class CassetteView(ctx: Context) : IntroView(ctx) {

    private var rot = 0f

    override fun drawIntro(c: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cw = min(w * 0.64f, h * 0.9f)
        val ch = cw * 0.64f

        val deckW = min(w * 0.94f, cw * 1.28f)
        val deckH = ch * 0.62f
        val deckTop = h * 0.74f
        val slotTop = deckTop + deckH * 0.26f
        val slotH = deckH * 0.17f
        val slotCenter = slotTop + slotH / 2f

        val p = ease(((raw - 0.10f) / 0.90f).coerceIn(0f, 1f))
        val sy = 1f - 0.12f * p
        val idleY = deckTop - ch * 0.62f
        val endY = slotCenter + ch * 0.5f * sy + 2f
        val cy = idleY + (endY - idleY) * p + sin(t * 2f) * ch * 0.03f * (1f - p)

        val speed = if (inserted) 300f else if (inserting) 25f + p * 275f else 22f
        rot = (rot + speed * dt) % 360f

        glow(c, cx, idleY, cw * 0.9f, "#337C5CFF")

        // Deck
        rect.set(cx - deckW / 2f, deckTop, cx + deckW / 2f, deckTop + deckH)
        paint.shader = LinearGradient(0f, deckTop, 0f, deckTop + deckH, col("#2E3448"), col("#12151F"), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, deckH * 0.2f, deckH * 0.2f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = col("#3C4560")
        c.drawRoundRect(rect, deckH * 0.2f, deckH * 0.2f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Pal.MUTED
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = deckH * 0.13f
        c.drawText("DTM TAPE DECK", cx - deckW * 0.42f, deckTop + deckH * 0.88f, paint)
        paint.color = col("#242A3D")
        for (i in 0 until 3) {
            c.drawCircle(cx + deckW * (0.18f + i * 0.09f), deckTop + deckH * 0.80f, deckH * 0.06f, paint)
        }

        // Kassette
        c.save()
        c.clipRect(0f, 0f, w, slotCenter)
        c.translate(cx, cy)
        c.scale(1f, sy)
        drawCassette(c, cw, ch)
        c.restore()

        // Schlitz + LED
        paint.color = col("#05060A")
        rect.set(cx - deckW * 0.40f, slotTop, cx + deckW * 0.40f, slotTop + slotH)
        c.drawRoundRect(rect, slotH / 2f, slotH / 2f, paint)
        paint.color = ledColor()
        c.drawCircle(cx + deckW * 0.44f, deckTop + deckH * 0.14f, deckH * 0.05f, paint)
    }

    private fun drawCassette(c: Canvas, cw: Float, ch: Float) {
        val hw = cw / 2f
        val hh = ch / 2f

        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(0f, -hh, 0f, hh, col("#343A50"), col("#1A1D2A"), Shader.TileMode.CLAMP)
        rect.set(-hw, -hh, hw, hh)
        c.drawRoundRect(rect, ch * 0.07f, ch * 0.07f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = col("#4A5272")
        c.drawRoundRect(rect, ch * 0.07f, ch * 0.07f, paint)
        paint.style = Paint.Style.FILL

        // Etikett
        paint.color = col("#EFE8D6")
        rect.set(-hw * 0.88f, -hh * 0.86f, hw * 0.88f, hh * 0.20f)
        c.drawRoundRect(rect, ch * 0.04f, ch * 0.04f, paint)
        paint.shader = LinearGradient(-hw * 0.88f, 0f, hw * 0.88f, 0f, Pal.ACCENT, Pal.ACCENT2, Shader.TileMode.CLAMP)
        rect.set(-hw * 0.88f, -hh * 0.60f, hw * 0.88f, -hh * 0.38f)
        c.drawRect(rect, paint)
        paint.shader = null
        paint.color = col("#22263A")
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = ch * 0.075f
        c.drawText("C-90 · STEREO", 0f, -hh * 0.68f, paint)
        paint.textSize = ch * 0.11f
        c.drawText("DTM VIDEO PLAYER", 0f, -hh * 0.14f, paint)
        paint.typeface = Typeface.DEFAULT

        // Fenster mit Spulen
        paint.color = col("#0B0C12")
        rect.set(-hw * 0.52f, -hh * 0.04f, hw * 0.52f, hh * 0.32f)
        c.drawRoundRect(rect, ch * 0.05f, ch * 0.05f, paint)
        drawReel(c, -hw * 0.27f, hh * 0.14f, hh * 0.14f)
        drawReel(c, hw * 0.27f, hh * 0.14f, hh * 0.14f)

        // Unterteil
        val path = Path()
        path.moveTo(-hw * 0.34f, hh)
        path.lineTo(-hw * 0.24f, hh * 0.58f)
        path.lineTo(hw * 0.24f, hh * 0.58f)
        path.lineTo(hw * 0.34f, hh)
        path.close()
        paint.color = col("#10121A")
        c.drawPath(path, paint)
        paint.color = col("#4A5272")
        c.drawCircle(-hw * 0.18f, hh * 0.80f, ch * 0.035f, paint)
        c.drawCircle(hw * 0.18f, hh * 0.80f, ch * 0.035f, paint)
        paint.color = col("#596083")
        for (sx in floatArrayOf(-1f, 1f)) {
            for (syy in floatArrayOf(-1f, 1f)) {
                c.drawCircle(sx * hw * 0.93f, syy * hh * 0.88f, ch * 0.025f, paint)
            }
        }
    }

    private fun drawReel(c: Canvas, x: Float, y: Float, r: Float) {
        paint.color = col("#E6E8F0")
        c.drawCircle(x, y, r, paint)
        paint.color = col("#0B0C12")
        c.drawCircle(x, y, r * 0.62f, paint)
        paint.color = col("#E6E8F0")
        c.save()
        c.translate(x, y)
        c.rotate(rot)
        for (i in 0 until 6) {
            c.rotate(60f)
            c.drawRect(-r * 0.07f, -r * 0.62f, r * 0.07f, 0f, paint)
        }
        c.restore()
        paint.color = col("#0B0C12")
        c.drawCircle(x, y, r * 0.16f, paint)
    }
}

// ====================================================================== USB-Stick im Anschluss

class UsbView(ctx: Context) : IntroView(ctx) {

    override fun drawIntro(c: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val bw = min(w * 0.30f, h * 0.17f)
        val bh = bw * 1.9f
        val connW = bw * 0.72f
        val connH = bw * 0.85f
        val total = bh + connH

        val portTop = h * 0.72f
        val clipY = portTop + bw * 0.20f
        val p = ease(((raw - 0.10f) / 0.90f).coerceIn(0f, 1f))
        val idleY = clipY - connH * 1.8f - total / 2f
        val endY = clipY - total / 2f + connH
        val cy = idleY + (endY - idleY) * p + sin(t * 2f) * bw * 0.05f * (1f - p)

        glow(c, cx, idleY, bw * 2.2f, "#337C5CFF")

        // Anschluss-Leiste
        val barW = bw * 3.2f
        val barH = bw * 0.7f
        rect.set(cx - barW / 2f, portTop, cx + barW / 2f, portTop + barH)
        paint.shader = LinearGradient(0f, portTop, 0f, portTop + barH, col("#2E3448"), col("#12151F"), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, barH * 0.3f, barH * 0.3f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = col("#3C4560")
        c.drawRoundRect(rect, barH * 0.3f, barH * 0.3f, paint)
        paint.style = Paint.Style.FILL
        paint.color = col("#05060A")
        rect.set(cx - connW * 0.62f, portTop + bw * 0.06f, cx + connW * 0.62f, portTop + bw * 0.34f)
        c.drawRoundRect(rect, bw * 0.05f, bw * 0.05f, paint)
        paint.color = Pal.MUTED
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = barH * 0.18f
        c.drawText("USB  ·  DTM VIDEO PLAYER", cx, portTop + barH * 0.85f, paint)

        // Stick, am Anschluss abgeschnitten
        c.save()
        c.clipRect(0f, 0f, w, clipY)
        c.translate(cx, cy)
        val top = -total / 2f
        val bodyBottom = top + bh

        paint.shader = LinearGradient(-bw / 2f, 0f, bw / 2f, 0f, col("#3A4163"), col("#1B1F33"), Shader.TileMode.CLAMP)
        rect.set(-bw / 2f, top, bw / 2f, bodyBottom)
        c.drawRoundRect(rect, bw * 0.22f, bw * 0.22f, paint)
        paint.shader = LinearGradient(-bw / 2f, 0f, bw / 2f, 0f, Pal.ACCENT, Pal.ACCENT2, Shader.TileMode.CLAMP)
        rect.set(-bw / 2f, top + bh * 0.46f, bw / 2f, top + bh * 0.64f)
        c.drawRect(rect, paint)
        paint.shader = null
        paint.color = Pal.TEXT
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = bw * 0.26f
        c.drawText("DTM", 0f, top + bh * 0.36f, paint)
        paint.textSize = bw * 0.12f
        c.drawText("VIDEO", 0f, top + bh * 0.80f, paint)
        paint.typeface = Typeface.DEFAULT
        paint.color = Pal.BG
        c.drawCircle(0f, top + bw * 0.18f, bw * 0.07f, paint)
        paint.color = ledColor()
        c.drawCircle(0f, bodyBottom - bw * 0.14f, bw * 0.05f, paint)

        // Metall-Stecker
        paint.shader = LinearGradient(-connW / 2f, 0f, connW / 2f, 0f, col("#F1F3F8"), col("#9AA2B8"), Shader.TileMode.CLAMP)
        rect.set(-connW / 2f, bodyBottom, connW / 2f, total / 2f)
        c.drawRect(rect, paint)
        paint.shader = null
        paint.color = col("#2A2F42")
        rect.set(-connW * 0.34f, bodyBottom + connH * 0.38f, -connW * 0.12f, bodyBottom + connH * 0.62f)
        c.drawRect(rect, paint)
        rect.set(connW * 0.12f, bodyBottom + connH * 0.38f, connW * 0.34f, bodyBottom + connH * 0.62f)
        c.drawRect(rect, paint)
        c.restore()
    }
}

// ====================================================================== DVD-Hülle, die aufklappt

class CaseView(ctx: Context) : IntroView(ctx) {

    private var discShader: Shader? = null
    private var rot = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        discShader = SweepGradient(
            0f, 0f,
            cols("#E9ECF4", "#B7A5FF", "#9FE9FF", "#F4F5FA", "#FFC6E3", "#C9D0E2", "#9FE9FF", "#E9ECF4"),
            null
        )
    }

    override fun drawIntro(c: Canvas, w: Float, h: Float) {
        val cw = min(w * 0.42f, h * 0.5f)
        val ch = cw * 1.4f
        val cy = h * 0.46f + sin(t * 2f) * ch * 0.01f * (1f - min(1f, raw * 6f))
        val top = cy - ch / 2f

        // Aufklappen in den ersten ~70 %, danach dreht sich die Disc hoch
        val open = ease(((raw - 0.08f) / 0.62f).coerceIn(0f, 1f))
        val angle = open * 172f
        val shift = -cw * 0.5f * open
        val left = w / 2f - cw / 2f + shift

        val speed = when {
            inserted -> 540f
            raw > 0.70f -> 40f + ((raw - 0.70f) / 0.30f) * 500f
            else -> 0f
        }
        rot = (rot + speed * dt) % 360f

        glow(c, w / 2f, cy, cw * 1.6f, "#337C5CFF")

        // Schatten
        paint.color = Color.argb(60, 0, 0, 0)
        rect.set(w / 2f - cw * (0.55f + open * 0.5f), top + ch + cw * 0.03f, w / 2f + cw * (0.55f + open * 0.5f), top + ch + cw * 0.12f)
        c.drawOval(rect, paint)

        // rechte Innenseite mit Disc
        rect.set(left, top, left + cw, top + ch)
        paint.shader = LinearGradient(left, top, left, top + ch, col("#1B1F33"), col("#0C0E18"), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, cw * 0.04f, cw * 0.04f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = col("#3C4560")
        c.drawRoundRect(rect, cw * 0.04f, cw * 0.04f, paint)
        paint.style = Paint.Style.FILL

        val dr = cw * 0.44f
        c.save()
        c.translate(left + cw / 2f, top + ch / 2f)
        c.rotate(rot)
        paint.shader = discShader
        c.drawCircle(0f, 0f, dr, paint)
        paint.shader = null
        paint.color = col("#D7DBE8")
        c.drawCircle(0f, 0f, dr * 0.30f, paint)
        paint.color = col("#8E96AE")
        c.drawCircle(0f, 0f, dr * 0.24f, paint)
        paint.color = col("#0C0E18")
        c.drawCircle(0f, 0f, dr * 0.09f, paint)
        c.restore()

        // Deckel (vorne, später innen links)
        val rad = Math.toRadians(angle.toDouble())
        val sx = cos(rad).toFloat()
        val sy = 1f - 0.09f * sin(rad).toFloat()
        c.save()
        c.translate(left, top + ch / 2f)
        c.scale(if (sx == 0f) 0.001f else sx, sy)
        c.translate(0f, -ch / 2f)
        if (angle < 90f) drawFront(c, cw, ch) else drawInnerLeft(c, cw, ch)
        c.restore()
    }

    private fun drawFront(c: Canvas, cw: Float, ch: Float) {
        rect.set(0f, 0f, cw, ch)
        paint.shader = LinearGradient(0f, 0f, cw, ch, col("#3A2C86"), col("#0E1230"), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, cw * 0.04f, cw * 0.04f, paint)
        paint.shader = null
        paint.color = col("#16102B")
        rect.set(0f, 0f, cw * 0.05f, ch)
        c.drawRect(rect, paint)
        glow(c, cw * 0.5f, ch * 0.36f, cw * 0.45f, "#557C5CFF")

        paint.color = Pal.TEXT
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = cw * 0.07f
        c.drawText("DVD VIDEO", cw * 0.5f, ch * 0.09f, paint)

        paint.shader = LinearGradient(0f, 0f, cw, 0f, Pal.ACCENT, Pal.ACCENT2, Shader.TileMode.CLAMP)
        c.drawCircle(cw * 0.5f, ch * 0.36f, cw * 0.22f, paint)
        paint.shader = null
        val tri = Path()
        tri.moveTo(cw * 0.44f, ch * 0.31f)
        tri.lineTo(cw * 0.60f, ch * 0.36f)
        tri.lineTo(cw * 0.44f, ch * 0.41f)
        tri.close()
        paint.color = Color.WHITE
        c.drawPath(tri, paint)

        paint.color = Pal.TEXT
        paint.textSize = cw * 0.22f
        c.drawText("DTM", cw * 0.5f, ch * 0.72f, paint)
        paint.textSize = cw * 0.09f
        c.drawText("VIDEO PLAYER", cw * 0.5f, ch * 0.80f, paint)
        paint.typeface = Typeface.DEFAULT

        paint.shader = LinearGradient(0f, 0f, cw, 0f, Pal.ACCENT, Pal.ACCENT2, Shader.TileMode.CLAMP)
        rect.set(0f, ch * 0.92f, cw, ch)
        c.drawRect(rect, paint)
        paint.shader = null
    }

    private fun drawInnerLeft(c: Canvas, cw: Float, ch: Float) {
        rect.set(0f, 0f, cw, ch)
        paint.shader = LinearGradient(0f, 0f, 0f, ch, col("#222744"), col("#0F1222"), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, cw * 0.04f, cw * 0.04f, paint)
        paint.shader = null
        // Einschub für das Heft
        paint.color = col("#2C3252")
        rect.set(cw * 0.10f, ch * 0.10f, cw * 0.92f, ch * 0.90f)
        c.drawRoundRect(rect, cw * 0.03f, cw * 0.03f, paint)
        paint.color = col("#3A4163")
        for (i in 0 until 6) {
            rect.set(cw * 0.18f, ch * (0.20f + i * 0.11f), cw * (0.50f + (i % 3) * 0.12f), ch * (0.20f + i * 0.11f) + 4f)
            c.drawRect(rect, paint)
        }
    }
}

// ====================================================================== Weltraum-Lauftext

class CrawlView(ctx: Context) : IntroView(ctx) {

    private val starX = FloatArray(150)
    private val starY = FloatArray(150)
    private val starR = FloatArray(150)
    private val starV = FloatArray(150)

    private val lines = listOf(
        "KAPITEL 1", "DER FILM BEGINNT", "",
        "Dein Kino. Deine Dateien.", "Jedes Video, jeder Ton,", "jede Disc – direkt auf",
        "deinem Bildschirm.", "", "Lehn dich zurück.", "Gleich geht es los …"
    )

    init {
        val rnd = java.util.Random(7)
        for (i in starX.indices) {
            starX[i] = rnd.nextFloat()
            starY[i] = rnd.nextFloat()
            starR[i] = 0.6f + rnd.nextFloat() * 1.8f
            starV[i] = 0.004f + rnd.nextFloat() * 0.02f
        }
    }

    private val yellow = Color.parseColor("#FFD54A")

    override fun drawIntro(c: Canvas, w: Float, h: Float) {
        val clip = Path()
        clip.addRoundRect(RectF(0f, 0f, w, h), 28f, 28f, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        c.drawColor(Color.parseColor("#02030A"))

        // Sterne
        paint.style = Paint.Style.FILL
        for (i in starX.indices) {
            starY[i] += starV[i] * dt * (1f + raw * 6f)
            if (starY[i] > 1f) starY[i] -= 1f
            paint.color = Color.argb((140 + 115 * sin(t * 2f + i)).toInt().coerceIn(30, 255), 255, 255, 255)
            c.drawCircle(starX[i] * w, starY[i] * h, starR[i], paint)
        }

        paint.textAlign = Paint.Align.CENTER

        // Einleitung (verblasst beim Start)
        val introA = (1f - ease((raw / 0.25f).coerceIn(0f, 1f)))
        if (introA > 0f) {
            paint.color = Color.argb((introA * 255).toInt(), 77, 166, 255)
            paint.typeface = Typeface.DEFAULT
            paint.textSize = w * 0.04f
            c.drawText("In einem Wohnzimmer, gar nicht weit von hier …", w / 2f, h * 0.14f, paint)
        }

        // Logo: schrumpft Richtung Horizont
        val k = ease((raw / 0.45f).coerceIn(0f, 1f))
        val logoScale = 1f - 0.95f * k
        val logoA = 1f - ease((raw / 0.55f).coerceIn(0f, 1f))
        if (logoA > 0f) {
            val ly = h * 0.44f - k * h * 0.22f
            paint.color = Color.argb((logoA * 255).toInt(), 255, 213, 74)
            paint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            paint.letterSpacing = 0.08f
            paint.textSize = w * 0.30f * logoScale
            c.drawText("DTM", w / 2f, ly, paint)
            paint.textSize = w * 0.10f * logoScale
            c.drawText("VIDEO PLAYER", w / 2f, ly + w * 0.12f * logoScale, paint)
            paint.letterSpacing = 0f
        }

        // Lauftext in Perspektive
        if (raw > 0.12f) {
            val horizon = h * 0.16f
            val n = lines.size
            val sp = 0.9f
            val len = n * sp
            val scroll = ((raw - 0.12f) / 0.88f).coerceIn(0f, 1f)
            paint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            for (i in 0 until n) {
                val text = lines[i]
                if (text.isEmpty()) continue
                val z = (n - 1 - i) * sp - len - 0.2f + scroll * (len + 6f)
                if (z < -0.2f) continue
                val f = 1f / (1f + z * 0.55f)
                if (f < 0.03f) continue
                val y = horizon + (h - horizon) * f
                paint.textSize = w * 0.075f * f
                paint.color = Color.argb((min(1f, f * 3f) * 255).toInt(), 255, 213, 74)
                c.drawText(text, w / 2f, y, paint)
            }
            paint.typeface = Typeface.DEFAULT
        }
        c.restore()
    }
}
