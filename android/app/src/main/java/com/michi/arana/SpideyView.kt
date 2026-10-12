package com.michi.arana

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Arañita héroe estilo chibi, dibujada a mano con Canvas (sin imágenes).
 * Todo se dibuja en una cuadrícula de 100×100 unidades que se escala al tamaño de la vista.
 */
class SpideyView(context: Context) : View(context) {

    enum class Mood { IDLE, WAVE, PARTY, LISTEN, THINK }

    var mood = Mood.IDLE
        private set
    var talking = false
        set(v) { field = v; invalidate() }
    /** Dibuja el hilo de telaraña del que cuelga. */
    var showThread = true

    private var moodUntil = 0L

    private val red = Color.parseColor("#E23636")
    private val redDark = Color.parseColor("#9E1B1B")
    private val blue = Color.parseColor("#2B4EB8")
    private val ink = Color.parseColor("#1A1A2E")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val oval = RectF()
    private var headShader: RadialGradient? = null
    private var shaderFor = -1f

    fun setMood(m: Mood, ms: Long = 0) {
        mood = m
        moodUntil = if (ms > 0) SystemClock.uptimeMillis() + ms else 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (moodUntil != 0L && now > moodUntil) { mood = Mood.IDLE; moodUntil = 0 }
        val t = now / 1000f

        val u = min(width, height) / 100f
        if (u <= 0f) return
        canvas.save()
        canvas.translate((width - 100 * u) / 2f, (height - 100 * u) / 2f)
        canvas.scale(u, u)

        val jump = when (mood) {
            Mood.PARTY -> -abs(sin(t * 7f)) * 9f
            else -> sin(t * 2.2f) * 1.6f
        }
        val sway = if (mood == Mood.IDLE) sin(t * 1.3f) * 3f else 0f

        // Hilo de telaraña desde arriba
        if (showThread) {
            line.color = Color.WHITE; line.strokeWidth = 1.6f
            canvas.drawLine(50f, 0f, 50f + sway * 0.3f, 14f + jump, line)
            line.color = Color.argb(90, 0, 0, 0); line.strokeWidth = 0.5f
            canvas.drawLine(50.8f, 0f, 50.8f + sway * 0.3f, 14f + jump, line)
        }

        // Sombra
        fill.color = Color.argb(45, 0, 0, 0)
        val sh = 1f + jump / 30f
        oval.set(50 - 17 * sh, 95f, 50 + 17 * sh, 99f); canvas.drawOval(oval, fill)

        canvas.save()
        canvas.translate(0f, jump)
        canvas.rotate(sway, 50f, 14f)

        drawBody(canvas, t)
        drawHead(canvas, t)

        canvas.restore()

        if (mood == Mood.LISTEN) drawListenRings(canvas, t)
        if (mood == Mood.THINK) drawThinkDots(canvas, t)

        canvas.restore()

        // Animación a ~30 fps mientras esté visible
        if (isShown) postInvalidateDelayed(33)
    }

    private fun drawBody(c: Canvas, t: Float) {
        line.color = ink; line.strokeWidth = 1.6f

        // Piernas (azules) con botas rojas
        for (s in intArrayOf(-1, 1)) {
            val x = 50f + s * 7f
            fill.color = blue
            oval.set(x - 5f, 78f, x + 5f, 94f); c.drawRoundRect(oval, 5f, 5f, fill); c.drawRoundRect(oval, 5f, 5f, line)
            fill.color = red
            oval.set(x - 6f, 89f, x + 6f + s * 1.5f, 96f); c.drawRoundRect(oval, 4f, 4f, fill); c.drawRoundRect(oval, 4f, 4f, line)
        }

        // Brazos (hacia fuera en reposo): el derecho saluda o se levanta en fiesta/escucha
        val waveArm = when (mood) {
            Mood.WAVE -> -120f + sin(t * 12f) * 25f
            Mood.PARTY -> -150f + sin(t * 14f) * 15f
            Mood.LISTEN -> -100f
            else -> -20f - sin(t * 2.2f) * 4f
        }
        val otherArm = when (mood) {
            Mood.PARTY -> 150f - sin(t * 14f) * 15f
            Mood.THINK -> 140f
            else -> 20f + sin(t * 2.2f) * 4f
        }
        drawArm(c, 61f, 68f, waveArm)
        drawArm(c, 39f, 68f, otherArm)

        // Torso: rojo al centro, azul a los lados
        oval.set(36f, 62f, 64f, 86f)
        fill.color = blue; c.drawRoundRect(oval, 12f, 12f, fill)
        path.reset()
        path.moveTo(42f, 62f); path.lineTo(58f, 62f); path.quadTo(60f, 74f, 56f, 86f); path.lineTo(44f, 86f); path.quadTo(40f, 74f, 42f, 62f); path.close()
        fill.color = red; c.drawPath(path, fill)
        c.drawRoundRect(oval, 12f, 12f, line)
        // Cinturón
        line.color = redDark; line.strokeWidth = 0.8f
        c.drawLine(38f, 80f, 62f, 80f, line)

        // Emblema de araña
        fill.color = ink
        oval.set(48.2f, 68f, 51.8f, 75f); c.drawOval(oval, fill)
        c.drawCircle(50f, 67.4f, 1.4f, fill)
        line.color = ink; line.strokeWidth = 0.7f
        for (s in intArrayOf(-1, 1)) {
            c.drawLine(50f, 70f, 50f + s * 5f, 66f, line)
            c.drawLine(50f, 71f, 50f + s * 5.5f, 70f, line)
            c.drawLine(50f, 72f, 50f + s * 5.5f, 74.5f, line)
            c.drawLine(50f, 73f, 50f + s * 4.5f, 78f, line)
        }
    }

    private fun drawArm(c: Canvas, x: Float, y: Float, deg: Float) {
        c.save()
        c.rotate(deg, x, y)
        line.color = ink; line.strokeWidth = 1.6f
        fill.color = red
        oval.set(x - 3.6f, y - 1f, x + 3.6f, y + 15f)
        c.drawRoundRect(oval, 3.6f, 3.6f, fill); c.drawRoundRect(oval, 3.6f, 3.6f, line)
        // Manita
        c.drawCircle(x, y + 15f, 4.2f, fill); c.drawCircle(x, y + 15f, 4.2f, line)
        c.restore()
    }

    private fun drawHead(c: Canvas, t: Float) {
        val cx = 50f; val cy = 40f; val rx = 30f; val ry = 27f

        if (shaderFor != rx) {
            headShader = RadialGradient(cx - 10f, cy - 12f, 40f, Color.parseColor("#FF6B6B"), red, Shader.TileMode.CLAMP)
            shaderFor = rx
        }
        oval.set(cx - rx, cy - ry, cx + rx, cy + ry)
        fill.shader = headShader
        c.drawOval(oval, fill)
        fill.shader = null

        // Telaraña: líneas radiales y anillos, recortadas a la cabeza
        c.save()
        path.reset(); path.addOval(oval, Path.Direction.CW); c.clipPath(path)
        line.color = redDark; line.strokeWidth = 0.75f
        val hx = cx; val hy = cy + 3f
        val n = 12
        for (i in 0 until n) {
            val a = (i * 2 * PI / n).toFloat()
            c.drawLine(hx, hy, hx + cos(a) * 45f, hy + sin(a) * 45f, line)
        }
        for (r in floatArrayOf(9f, 17f, 25f, 33f)) {
            path.reset()
            for (i in 0..n) {
                val a = (i * 2 * PI / n).toFloat()
                val px = hx + cos(a) * r; val py = hy + sin(a) * r * 0.92f
                if (i == 0) path.moveTo(px, py) else {
                    val am = ((i - 0.5f) * 2 * PI / n).toFloat()
                    val cr = r * 0.82f
                    path.quadTo(hx + cos(am) * cr, hy + sin(am) * cr * 0.92f, px, py)
                }
            }
            c.drawPath(path, line)
        }
        c.restore()

        line.color = ink; line.strokeWidth = 1.8f
        c.drawOval(oval, line)

        // Mofletes
        fill.color = Color.argb(110, 255, 143, 163)
        c.drawCircle(cx - 20f, cy + 14f, 4f, fill)
        c.drawCircle(cx + 20f, cy + 14f, 4f, fill)

        // Ojos grandes estilo máscara
        val happy = talking || mood == Mood.PARTY || mood == Mood.WAVE
        val blink = (t % 4.2f) > 4.05f
        for (s in intArrayOf(-1, 1)) drawEye(c, cx + s * 12f, cy + 1f, s, happy, blink, t)

        // Boquita: la máscara se mueve cuando habla
        if (talking) {
            val open = 1.2f + abs(sin(t * 13f)) * 2.6f
            fill.color = redDark
            oval.set(cx - 3f, cy + 17f - open / 2, cx + 3f, cy + 17f + open / 2)
            c.drawOval(oval, fill)
        } else {
            line.color = redDark; line.strokeWidth = 1.1f
            oval.set(cx - 3f, cy + 14f, cx + 3f, cy + 19f)
            c.drawArc(oval, 20f, 140f, false, line)
        }
    }

    private fun drawEye(c: Canvas, x: Float, y: Float, side: Int, happy: Boolean, blink: Boolean, t: Float) {
        c.save()
        c.translate(x, y)
        c.scale(side.toFloat(), 1f)  // el ojo izquierdo es el espejo del derecho
        line.color = ink
        if (blink || (happy && (t % 1.6f) < 0.8f && talking)) {
            // Ojo cerrado/contento: arco ^
            line.strokeWidth = 2.6f
            path.reset(); path.moveTo(-6f, 2f); path.quadTo(0f, -5f, 6f, 2f)
            c.drawPath(path, line)
        } else {
            val sy = if (mood == Mood.LISTEN) 1.12f else 1f
            c.scale(1f, sy)
            // Ojo grande, más alto hacia fuera (mirada alegre)
            path.reset()
            path.moveTo(-7.5f, 3f)
            path.quadTo(-6f, -8f, 5f, -8.5f)
            path.quadTo(10f, -8f, 9.5f, 1f)
            path.quadTo(9f, 9.5f, 1f, 9.5f)
            path.quadTo(-7f, 9.5f, -7.5f, 3f)
            path.close()
            fill.color = Color.WHITE
            c.drawPath(path, fill)
            line.strokeWidth = 2.8f
            c.drawPath(path, line)
            // Brillito
            fill.color = Color.argb(180, 200, 220, 255)
            c.drawCircle(3f, -3.5f, 2.2f, fill)
        }
        c.restore()
    }

    private fun drawListenRings(c: Canvas, t: Float) {
        line.strokeWidth = 1.4f
        for (i in 0..2) {
            val p = ((t * 0.9f + i / 3f) % 1f)
            line.color = Color.argb(((1 - p) * 200).toInt(), 70, 160, 255)
            c.drawCircle(50f, 42f, 30f + p * 18f, line)
        }
    }

    private fun drawThinkDots(c: Canvas, t: Float) {
        fill.color = Color.WHITE
        line.color = ink; line.strokeWidth = 1f
        for (i in 0..2) {
            val r = 2f + i * 1.2f
            val yy = 14f - i * 5f + sin(t * 4f + i) * 1.2f
            c.drawCircle(76f + i * 6f, yy, r, fill); c.drawCircle(76f + i * 6f, yy, r, line)
        }
    }
}
