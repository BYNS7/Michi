package com.michi.arana

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * BYNS: figura estilo bloques de construcción (minifigura) dibujada con Canvas.
 * Tres trajes (soldado, araña roja, araña negra) y movimientos tipo «brickfilm»:
 * extremidades rígidas que giran en hombros y caderas, saltos, volteretas, etc.
 *
 * Todo se dibuja en una cuadrícula de 100×100 que se escala a la vista.
 * Ángulos de brazos/piernas en grados, sentido horario; 0 = hacia abajo.
 */
class AvatarView(context: Context) : View(context) {

    enum class Skin(val title: String) {
        SOLDADO("BYNS Soldado"), ARANA_ROJA("BYNS Araña Roja"), ARANA_NEGRA("BYNS Araña Negra")
    }

    enum class Move { IDLE, WAVE, PARTY, LISTEN, THINK, AIM, SALUTE, WALK, DANCE, FLIP, SWING, WEB, TRANSFORM }

    var skin = Skin.SOLDADO
        private set
    var move = Move.IDLE
        private set
    var talking = false
        set(v) { field = v; invalidate() }
    /** Hacia dónde apunta/dispara la telaraña: +1 derecha, -1 izquierda. */
    var facing = -1

    private var moveStart = 0L
    private var moveUntil = 0L
    private var pendingSkin: Skin? = null
    private var bricks: List<FloatArray> = emptyList()

    // --- colores
    private val ink = Color.parseColor("#1A1A22")
    private val yellow = Color.parseColor("#F6CB2F")
    private val tan = Color.parseColor("#B7996A")
    private val tanDark = Color.parseColor("#8C7148")
    private val camoBase = Color.parseColor("#8F8A62")
    private val camo = intArrayOf(Color.parseColor("#5C6A3A"), Color.parseColor("#6E5537"), Color.parseColor("#C2B183"), Color.parseColor("#4A4F2E"))
    private val spRed = Color.parseColor("#D9262B")
    private val spRedDark = Color.parseColor("#8E1418")
    private val spBlue = Color.parseColor("#2357C2")
    private val black = Color.parseColor("#17171C")
    private val navy = Color.parseColor("#1D3A7A")
    private val ice = Color.parseColor("#9FD8F5")
    private val iceHand = Color.parseColor("#8EC4F0")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    // Manchas de camuflaje fijas (x, y, rx, ry, color)
    private val camoBlobs: List<FloatArray> = Random(7).let { r ->
        List(70) { floatArrayOf(r.nextFloat() * 110 - 5, r.nextFloat() * 110 - 5, 2f + r.nextFloat() * 3.5f, 1.5f + r.nextFloat() * 2.5f, r.nextInt(4).toFloat()) }
    }

    fun setSkinNow(s: Skin) { skin = s; invalidate() }

    fun play(m: Move, ms: Long = 0) {
        move = m
        moveStart = SystemClock.uptimeMillis()
        moveUntil = if (ms > 0) moveStart + ms else 0
        invalidate()
    }

    /** Animación de transformación: gira, suelta piezas y cambia de traje a mitad del giro. */
    fun transformTo(s: Skin) {
        pendingSkin = s
        val r = Random(SystemClock.uptimeMillis())
        val cols = intArrayOf(spRed, spBlue, yellow, tan, black, ice, Color.WHITE)
        bricks = List(14) {
            val a = r.nextFloat() * 2 * PI.toFloat()
            floatArrayOf(cos(a) * (30 + r.nextFloat() * 40), sin(a) * (30 + r.nextFloat() * 30) - 40, cols[r.nextInt(cols.size)].toFloat(), r.nextFloat() * 360)
        }
        play(Move.TRANSFORM, 1100)
    }

    // ------------------------------------------------------------------ pose

    private class Pose {
        var x = 0f; var y = 0f; var rot = 0f; var pivotY = 60f; var scaleX = 1f
        var aL = 10f; var aR = -10f; var lL = 0f; var lR = 0f; var shL = 1f; var shR = 1f
        var tilt = 0f; var look = 0f
        var swingRot = 0f
    }
    private val p = Pose()

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (moveUntil != 0L && now > moveUntil) {
            pendingSkin?.let { skin = it }; pendingSkin = null
            move = Move.IDLE; moveUntil = 0
        }
        val g = now / 1000f
        val t = (now - moveStart) / 1000f
        computePose(g, t)

        val u = min(width, height) / 100f
        if (u <= 0f) return
        canvas.save()
        canvas.translate((width - 100 * u) / 2f, (height - 100 * u) / 2f)
        canvas.scale(u, u)

        val spider = skin != Skin.SOLDADO
        // Sombra en el suelo
        if (move != Move.SWING) {
            fill.color = Color.argb(45, 0, 0, 0)
            val k = 1f + p.y / 40f
            rect.set(50 - 22 * k, 95.5f, 50 + 22 * k, 99f); canvas.drawOval(rect, fill)
        }

        canvas.save()
        if (move == Move.SWING) {
            canvas.rotate(p.swingRot, 50f, -30f)
            line.color = Color.WHITE; line.strokeWidth = 1.4f
            canvas.drawLine(50f, -30f, 66.8f, 21f, line)
        } else if (spider && move != Move.FLIP && move != Move.PARTY && move != Move.TRANSFORM) {
            // Hilo de telaraña del que cuelga
            line.color = Color.argb(220, 255, 255, 255); line.strokeWidth = 1.1f
            canvas.drawLine(50f, -10f, 50f, 8f + p.y, line)
        }
        canvas.translate(p.x, p.y)
        canvas.rotate(p.rot, 50f, p.pivotY)
        canvas.scale(p.scaleX, 1f, 50f, 0f)
        if (move == Move.AIM && facing < 0) canvas.scale(-1f, 1f, 50f, 0f)

        drawLegs(canvas)
        drawTorso(canvas)
        drawHead(canvas, g)
        drawArm(canvas, 36f, 41f, p.aL, left = true)
        drawArm(canvas, 64f, 41f, p.aR, left = false)
        if (move == Move.AIM) drawRifle(canvas, t)
        if (move == Move.WEB) drawWebShot(canvas, t)
        canvas.restore()

        if (move == Move.TRANSFORM) drawBricks(canvas, t)
        if (move == Move.LISTEN) drawListenRings(canvas, g)
        if (move == Move.THINK) drawThinkDots(canvas, g)

        canvas.restore()
        if (isShown) postInvalidateDelayed(33)
    }

    private fun computePose(g: Float, t: Float) {
        p.x = 0f; p.y = 0f; p.rot = 0f; p.pivotY = 60f; p.scaleX = 1f
        p.aL = 10f; p.aR = -10f; p.lL = 0f; p.lR = 0f; p.shL = 1f; p.shR = 1f
        p.tilt = 0f; p.look = 0f; p.swingRot = 0f
        val breathe = sin(g * 2f) * 0.5f
        when (move) {
            Move.IDLE -> {
                p.y = breathe
                p.aL = 10f + sin(g * 2f) * 3f; p.aR = -10f - sin(g * 2f) * 3f
                // De vez en cuando mira a los lados, como una minifigura girando la cabeza
                val cyc = g % 9f
                p.look = when { cyc > 7.5f -> 3f; cyc > 6f -> -3f; else -> 0f }
            }
            Move.WAVE -> { p.aR = -150f + sin(t * 12f) * 22f; p.tilt = -6f; p.y = breathe }
            Move.PARTY -> {
                val j = abs(sin(t * 6f)); p.y = -j * 14f
                p.aL = 160f + sin(t * 12f) * 10f; p.aR = -160f - sin(t * 12f) * 10f
                p.lL = j * 14f; p.lR = -j * 14f
            }
            Move.LISTEN -> { p.aR = -120f; p.tilt = 7f; p.look = 2f; p.y = breathe }
            Move.THINK -> { p.aL = 150f; p.tilt = -8f; p.look = -2f; p.y = breathe }
            Move.AIM -> {
                // Posición de tiro: rodillas flexionadas, brazos al frente sujetando el fusil
                val recoil = if ((t % 1.4f) < 0.12f && t > 0.5f) -1.5f else 0f
                p.aR = -90f; p.aL = -72f; p.look = 3f; p.tilt = 4f
                p.y = 2.5f; p.lL = 9f; p.lR = -9f; p.shL = 0.92f; p.shR = 0.92f
                p.x = recoil
            }
            Move.SALUTE -> { p.aR = -186f; p.tilt = -2f; p.lL = 3f; p.lR = -3f }
            Move.WALK -> {
                val s = sin(t * 8f)
                p.lL = s * 22f; p.lR = -s * 22f
                p.shL = 1f - abs(s) * 0.08f; p.shR = p.shL
                p.aL = 10f - s * 28f; p.aR = -10f - s * 28f
                p.y = -abs(cos(t * 8f)) * 1.8f
            }
            Move.DANCE -> {
                val s = sin(t * 7f)
                p.rot = s * 8f
                p.aL = if (s > 0) 160f else 40f; p.aR = if (s > 0) -40f else -160f
                p.lL = if (s > 0) 18f else 0f; p.lR = if (s < 0) -18f else 0f
                p.y = -abs(s) * 4f; p.tilt = -s * 6f
            }
            Move.FLIP -> {
                val k = (t / 0.9f).coerceIn(0f, 1f)
                p.y = -sin(PI.toFloat() * k) * 26f
                p.rot = 360f * k * (if (facing < 0) -1 else 1)
                p.pivotY = 52f
                p.aL = 120f; p.aR = -120f; p.lL = 25f; p.lR = -25f
            }
            Move.SWING -> {
                p.swingRot = sin(t * 2.6f) * 14f
                p.aR = -172f; p.aL = 40f + sin(t * 2.6f) * 10f
                p.lL = 20f; p.lR = -8f - sin(t * 2.6f) * 10f
            }
            Move.WEB -> {
                if (facing > 0) { p.aR = -90f; p.aL = 25f } else { p.aL = 90f; p.aR = -25f }
                p.look = 3f * facing; p.lL = 6f; p.lR = -6f
            }
            Move.TRANSFORM -> {
                val k = (t / 1.1f).coerceIn(0f, 1f)
                p.scaleX = cos(k * 4f * PI.toFloat()).let { if (abs(it) < 0.08f) 0.08f * (if (it < 0) -1 else 1) else it }
                p.y = -sin(PI.toFloat() * k) * 12f
                p.aL = 120f; p.aR = -120f
                if (k > 0.5f) pendingSkin?.let { skin = it }
            }
        }
        if (talking) { p.tilt += sin(g * 9f) * 2.5f }
    }

    // ------------------------------------------------------------------ partes

    private fun outline(w: Float = 1.2f) { line.color = ink; line.strokeWidth = w }

    private fun legColors(): IntArray = when (skin) {
        Skin.SOLDADO -> intArrayOf(camoBase, tan)
        Skin.ARANA_ROJA -> intArrayOf(spBlue, spRed)
        Skin.ARANA_NEGRA -> intArrayOf(black, black)
    }

    private fun drawLegs(c: Canvas) {
        val (legC, footC) = legColors().let { it[0] to it[1] }
        // Cadera
        rect.set(31f, 63.5f, 69f, 69.5f)
        fill.color = legC; c.drawRect(rect, fill)
        if (skin == Skin.SOLDADO) camo(c, Path().apply { addRect(RectF(rect), Path.Direction.CW) })
        outline(); c.drawRect(rect, line)
        for ((i, side) in listOf(-1, 1).withIndex()) {
            val pivotX = if (side < 0) 40.5f else 59.5f
            val ang = if (i == 0) p.lL else p.lR
            val sh = if (i == 0) p.shL else p.shR
            c.save()
            c.rotate(ang, pivotX, 69f)
            c.scale(1f, sh, pivotX, 69f)
            val x1 = if (side < 0) 31f else 50.6f
            val x2 = if (side < 0) 49.4f else 69f
            rect.set(x1, 69f, x2, 95f)
            path.reset(); path.addRoundRect(rect, floatArrayOf(0f, 0f, 0f, 0f, 1.5f, 1.5f, 1.5f, 1.5f), Path.Direction.CW)
            fill.color = legC; c.drawPath(path, fill)
            when (skin) {
                Skin.SOLDADO -> {
                    camo(c, path)
                    // Rodillera y bota
                    fill.color = tan
                    rect.set(x1 + 3f, 75f, x2 - 3f, 81.5f); c.drawRoundRect(rect, 2f, 2f, fill)
                    outline(0.7f); c.drawRoundRect(rect, 2f, 2f, line)
                    rect.set(x1, 86f, x2, 95f); c.drawRect(rect, fill.apply { color = footC })
                    line.color = tanDark; line.strokeWidth = 0.6f
                    val mx = (x1 + x2) / 2
                    c.drawLine(mx - 3f, 87.5f, mx + 3f, 90f, line); c.drawLine(mx + 3f, 87.5f, mx - 3f, 90f, line)
                    c.drawLine(x1, 93f, x2, 93f, line)
                }
                Skin.ARANA_ROJA -> {
                    rect.set(x1, 85f, x2, 95f); fill.color = footC; c.drawRect(rect, fill)
                    line.color = Color.argb(120, 0, 0, 0); line.strokeWidth = 0.5f
                    c.drawLine(x1, 85f, x2, 85f, line)
                }
                Skin.ARANA_NEGRA -> {
                    fill.color = navy
                    path.reset(); path.moveTo(x1, 74f); path.lineTo(x1 + 7f, 79f); path.lineTo(x1 + 3f, 86f); path.lineTo(x1, 84f); path.close()
                    c.drawPath(path, fill)
                    zigzag(c, x2 - 2f, 72f, x2 - 7f, 90f, ice)
                    line.color = spRed; line.strokeWidth = 1f; c.drawLine(x1 + 0.5f, 94.5f, x2 - 0.5f, 94.5f, line)
                }
            }
            outline()
            rect.set(x1, 69f, x2, 95f)
            path.reset(); path.addRoundRect(rect, floatArrayOf(0f, 0f, 0f, 0f, 1.5f, 1.5f, 1.5f, 1.5f), Path.Direction.CW)
            c.drawPath(path, line)
            c.restore()
        }
    }

    private fun torsoPath(): Path = Path().apply {
        moveTo(37f, 37f); lineTo(63f, 37f); lineTo(69f, 64f); lineTo(31f, 64f); close()
    }

    private fun drawTorso(c: Canvas) {
        val tp = torsoPath()
        when (skin) {
            Skin.SOLDADO -> {
                fill.color = camoBase; c.drawPath(tp, fill)
                camo(c, tp)
                // Cuello verde
                fill.color = Color.parseColor("#4F5E33")
                path.reset(); path.moveTo(45f, 37f); path.lineTo(55f, 37f); path.lineTo(50f, 42f); path.close(); c.drawPath(path, fill)
                // Chaleco porta placas
                fill.color = tan
                rect.set(38.5f, 41f, 61.5f, 62f); c.drawRoundRect(rect, 1.5f, 1.5f, fill)
                rect.set(38f, 36.5f, 42.5f, 42f); c.drawRect(rect, fill)
                rect.set(57.5f, 36.5f, 62f, 42f); c.drawRect(rect, fill)
                fill.color = tanDark
                var yy = 52f
                while (yy < 61f) {
                    var xx = 40.5f
                    while (xx < 59f) { rect.set(xx, yy, xx + 3.2f, yy + 1.2f); c.drawRoundRect(rect, 0.5f, 0.5f, fill); xx += 4.4f }
                    yy += 2.6f
                }
                line.color = Color.parseColor("#9A9A9A"); line.strokeWidth = 0.8f
                c.drawRect(39f, 38.5f, 41.5f, 40.5f, line); c.drawRect(58.5f, 38.5f, 61f, 40.5f, line)
                outline(0.7f); rect.set(38.5f, 41f, 61.5f, 62f); c.drawRoundRect(rect, 1.5f, 1.5f, line)
                bolivia(c, 45f, 43.5f, 10f, 6.5f)
            }
            Skin.ARANA_ROJA -> {
                fill.color = spBlue; c.drawPath(tp, fill)
                val center = Path().apply { moveTo(41f, 37f); lineTo(59f, 37f); lineTo(56f, 64f); lineTo(44f, 64f); close() }
                fill.color = spRed; c.drawPath(center, fill)
                c.save(); c.clipPath(center)
                webLines(c, 50f, 36f, 32f, spRedDark, 0.5f, 9)
                c.restore()
                spiderEmblem(c, 50f, 45f, 1f, ink)
                line.color = Color.argb(70, 0, 0, 0); line.strokeWidth = 0.6f
                c.drawLine(36f, 60f, 64f, 60f, line)
            }
            Skin.ARANA_NEGRA -> {
                fill.color = black; c.drawPath(tp, fill)
                c.save(); c.clipPath(tp)
                fill.color = navy
                path.reset(); path.moveTo(40f, 52f); path.lineTo(52f, 47f); path.lineTo(62f, 58f); path.lineTo(48f, 65f); path.close(); c.drawPath(path, fill)
                zigzag(c, 33f, 40f, 44f, 62f, ice)
                zigzag(c, 66f, 39f, 58f, 63f, ice)
                c.restore()
                spiderEmblem(c, 50f, 48f, 1.5f, spRed)
            }
        }
        outline(); c.drawPath(tp, line)
    }

    private fun drawHead(c: Canvas, g: Float) {
        c.save()
        c.rotate(p.tilt, 50f, 36f)
        // Cuello
        fill.color = if (skin == Skin.SOLDADO) yellow else if (skin == Skin.ARANA_ROJA) spRed else black
        rect.set(45f, 33f, 55f, 38f); c.drawRect(rect, fill)
        // Botón superior (stud)
        rect.set(44.5f, 6f, 55.5f, 11f); c.drawRoundRect(rect, 1.5f, 1.5f, fill); outline(1f); c.drawRoundRect(rect, 1.5f, 1.5f, line)
        // Cabeza cilíndrica
        val head = RectF(37f, 9.5f, 63f, 34f)
        c.drawRoundRect(head, 6f, 6f, fill)
        // Brillo del plástico
        fill.color = Color.argb(60, 255, 255, 255)
        rect.set(39.5f, 12f, 43f, 30f); c.drawRoundRect(rect, 2f, 2f, fill)

        val lx = p.look
        val blink = (g % 4.3f) > 4.15f
        when (skin) {
            Skin.SOLDADO -> soldierFace(c, lx, blink, g)
            Skin.ARANA_ROJA -> spiderFace(c, head, lx, blink, red = true, g = g)
            Skin.ARANA_NEGRA -> spiderFace(c, head, lx, blink, red = false, g = g)
        }
        outline(); c.drawRoundRect(head, 6f, 6f, line)
        if (skin == Skin.SOLDADO) helmet(c)
        c.restore()
    }

    private fun soldierFace(c: Canvas, lx: Float, blink: Boolean, g: Float) {
        // Cejas serias
        line.color = ink; line.strokeWidth = 1.5f
        c.drawLine(40.5f + lx, 20.2f, 47.5f + lx, 21.3f, line)
        c.drawLine(59.5f + lx, 20.2f, 52.5f + lx, 21.3f, line)
        // Lentes
        line.strokeWidth = 1f
        rect.set(39.8f + lx, 22.2f, 48.6f + lx, 27.6f); c.drawRoundRect(rect, 1.2f, 1.2f, line)
        rect.set(51.4f + lx, 22.2f, 60.2f + lx, 27.6f); c.drawRoundRect(rect, 1.2f, 1.2f, line)
        c.drawLine(48.6f + lx, 23.8f, 51.4f + lx, 23.8f, line)
        c.drawLine(39.8f + lx, 23.5f, 37.2f, 23f, line); c.drawLine(60.2f + lx, 23.5f, 62.8f, 23f, line)
        // Ojos
        if (blink) {
            line.strokeWidth = 0.9f
            c.drawLine(42.8f + lx, 25f, 45.6f + lx, 25f, line); c.drawLine(54.4f + lx, 25f, 57.2f + lx, 25f, line)
        } else {
            fill.color = ink
            c.drawCircle(44.2f + lx * 1.15f, 24.9f, 1.25f, fill); c.drawCircle(55.8f + lx * 1.15f, 24.9f, 1.25f, fill)
            fill.color = Color.WHITE
            c.drawCircle(44.6f + lx * 1.15f, 24.4f, 0.4f, fill); c.drawCircle(56.2f + lx * 1.15f, 24.4f, 0.4f, fill)
        }
        // Boca: media sonrisa o hablando
        if (talking) {
            val open = 0.8f + abs(sin(g * 13f)) * 1.8f
            fill.color = Color.parseColor("#5A2A1A")
            rect.set(47f + lx, 30.5f - open / 2, 53f + lx, 30.5f + open / 2); c.drawOval(rect, fill)
        } else {
            line.color = ink; line.strokeWidth = 0.9f
            path.reset(); path.moveTo(46.5f + lx, 30.8f); path.quadTo(51f + lx, 31.4f, 54f + lx, 30f)
            c.drawPath(path, line)
        }
    }

    private fun helmet(c: Canvas) {
        fill.color = tan
        path.reset()
        path.moveTo(34.5f, 20f)
        path.cubicTo(34f, 6f, 66f, 6f, 65.5f, 20f)
        path.close()
        c.drawPath(path, fill)
        outline(1f); c.drawPath(path, line)
        // Borde del casco
        rect.set(34f, 16.5f, 66f, 20.2f); fill.color = tanDark; c.drawRoundRect(rect, 1.4f, 1.4f, fill)
        outline(0.8f); c.drawRoundRect(rect, 1.4f, 1.4f, line)
        // Soporte frontal y rieles laterales
        fill.color = tanDark
        rect.set(46f, 6.5f, 54f, 12f); c.drawRoundRect(rect, 1f, 1f, fill); c.drawRoundRect(rect, 1f, 1f, line)
        rect.set(48f, 8f, 52f, 10.5f); fill.color = tan; c.drawRect(rect, fill)
        fill.color = tanDark
        rect.set(32.5f, 12f, 36f, 18f); c.drawRoundRect(rect, 0.8f, 0.8f, fill); c.drawRoundRect(rect, 0.8f, 0.8f, line)
        rect.set(64f, 12f, 67.5f, 18f); c.drawRoundRect(rect, 0.8f, 0.8f, fill); c.drawRoundRect(rect, 0.8f, 0.8f, line)
        // Correas
        line.color = Color.parseColor("#3E3A30"); line.strokeWidth = 0.9f
        c.drawLine(37.5f, 20f, 38.5f, 31f, line); c.drawLine(62.5f, 20f, 61.5f, 31f, line)
    }

    private fun spiderFace(c: Canvas, head: RectF, lx: Float, blink: Boolean, red: Boolean, g: Float) {
        c.save()
        path.reset(); path.addRoundRect(head, 6f, 6f, Path.Direction.CW); c.clipPath(path)
        if (red) {
            webLines(c, 50f + lx, 22f, 30f, spRedDark, 0.55f, 12)
        } else {
            // Puntos y rayos celestes del traje negro
            fill.color = ice
            for (i in 0..4) for (j in 0..2) {
                c.drawCircle(41f + i * 1.6f + lx, 28f + j * 1.6f + (i % 2) * 0.8f, 0.45f, fill)
                c.drawCircle(53f + i * 1.6f + lx, 28f + j * 1.6f + (i % 2) * 0.8f, 0.45f, fill)
            }
            zigzag(c, 37f, 14f, 42f, 30f, ice)
            zigzag(c, 63f, 13f, 58f, 30f, ice)
        }
        c.restore()
        for (s in intArrayOf(-1, 1)) {
            c.save()
            c.translate(50f + s * 6.6f + lx, 21.5f)
            c.scale(s.toFloat(), if (blink) 0.15f else if (talking) 0.8f + abs(sin(g * 10f)) * 0.2f else 1f)
            // Ojo de máscara: grande, puntiagudo hacia el centro
            path.reset()
            path.moveTo(-4.8f, 3.8f)
            path.quadTo(-4.5f, -3.2f, 1.5f, -4.4f)
            path.quadTo(6.6f, -4.6f, 6.4f, 0.8f)
            path.quadTo(6f, 5.8f, 0.5f, 5.8f)
            path.quadTo(-3.5f, 5.8f, -4.8f, 3.8f)
            path.close()
            fill.color = Color.WHITE; c.drawPath(path, fill)
            if (!red) { line.color = spRed; line.strokeWidth = 2.6f; c.drawPath(path, line) }
            line.color = ink; line.strokeWidth = if (red) 1.9f else 0.9f; c.drawPath(path, line)
            c.restore()
        }
    }

    private fun drawArm(c: Canvas, sx: Float, sy: Float, ang: Float, left: Boolean) {
        val (armC, handC) = when (skin) {
            Skin.SOLDADO -> camoBase to yellow
            Skin.ARANA_ROJA -> spBlue to spRed
            Skin.ARANA_NEGRA -> black to iceHand
        }
        c.save()
        c.rotate(ang, sx, sy)
        // Brazo: tubo ligeramente curvo
        path.reset()
        path.moveTo(sx - 4f, sy - 2f); path.lineTo(sx + 4f, sy - 2f)
        path.lineTo(sx + 3.6f, sy + 17f); path.lineTo(sx - 3.6f, sy + 17f); path.close()
        fill.color = armC; c.drawPath(path, fill)
        when (skin) {
            Skin.SOLDADO -> {
                camo(c, path)
                if (left) bolivia(c, sx - 2.6f, sy + 2f, 5.2f, 3.6f) // parche en el hombro
            }
            Skin.ARANA_ROJA -> {
                fill.color = spRed; rect.set(sx - 4f, sy - 2f, sx + 4f, sy + 3f); c.drawRect(rect, fill)
            }
            Skin.ARANA_NEGRA -> zigzag(c, sx - 2f, sy, sx + 2f, sy + 15f, ice)
        }
        outline(1f); c.drawPath(path, line)
        // Manita en forma de C
        c.translate(sx, sy + 20f)
        fill.color = handC
        path.reset()
        path.addCircle(0f, 0f, 4.2f, Path.Direction.CW)
        path.addCircle(0f, 1.6f, 2f, Path.Direction.CCW)
        path.fillType = Path.FillType.EVEN_ODD
        c.drawPath(path, fill)
        outline(0.9f); c.drawCircle(0f, 0f, 4.2f, line)
        path.fillType = Path.FillType.WINDING
        // muñeca
        rect.set(-1.8f, -5.5f, 1.8f, -3.2f); fill.color = handC; c.drawRect(rect, fill)
        c.restore()
    }

    private fun drawRifle(c: Canvas, t: Float) {
        // Fusil de juguete apuntando a la derecha (se espeja si mira a la izquierda)
        val dark = Color.parseColor("#2E2E2E")
        val body = if (skin == Skin.SOLDADO) Color.parseColor("#6B5A3E") else dark
        fill.color = body
        rect.set(54f, 42.5f, 63f, 48f); c.drawRoundRect(rect, 1f, 1f, fill)      // culata
        fill.color = dark
        rect.set(62f, 41f, 86f, 45.5f); c.drawRoundRect(rect, 0.8f, 0.8f, fill)  // cajón
        rect.set(85f, 42.2f, 99f, 44f); c.drawRect(rect, fill)                    // cañón
        rect.set(73f, 45f, 77f, 52.5f); c.drawRoundRect(rect, 0.8f, 0.8f, fill)   // cargador
        rect.set(66f, 45f, 69f, 50f); c.drawRect(rect, fill)                      // empuñadura
        rect.set(70f, 38.2f, 78f, 41f); c.drawRect(rect, fill)                    // mira
        outline(0.6f)
        rect.set(62f, 41f, 86f, 45.5f); c.drawRoundRect(rect, 0.8f, 0.8f, line)
        if ((t % 1.4f) < 0.1f && t > 0.5f) {
            fill.color = Color.parseColor("#FFD54A")
            path.reset(); path.moveTo(99f, 43f); path.lineTo(103f, 39f); path.lineTo(102f, 43f); path.lineTo(106f, 44f); path.lineTo(102f, 45f); path.lineTo(103f, 48f); path.close()
            c.drawPath(path, fill)
        }
    }

    private fun drawWebShot(c: Canvas, t: Float) {
        val k = (t / 0.35f).coerceIn(0f, 1f)
        // Mano disparando (brazo horizontal): calcula la posición de la mano según el lado
        val hx = if (facing > 0) 84f else 16f
        val endX = hx + facing * 50f * k
        line.color = Color.WHITE; line.strokeWidth = 1.2f
        c.drawLine(hx, 41f, endX, 30f, line)
        if (k >= 1f) {
            line.strokeWidth = 0.8f
            for (i in 0 until 6) {
                val a = i * PI.toFloat() / 3f
                c.drawLine(endX, 30f, endX + cos(a) * 5f, 30f + sin(a) * 5f, line)
            }
        }
    }

    private fun drawBricks(c: Canvas, t: Float) {
        val k = (t / 1.1f).coerceIn(0f, 1f)
        for (b in bricks) {
            val x = 50f + b[0] * k
            val y = 50f + b[1] * k + 60f * k * k
            c.save(); c.rotate(b[3] + k * 300f, x, y)
            fill.color = b[2].toInt()
            rect.set(x - 3f, y - 1.5f, x + 3f, y + 1.5f); c.drawRect(rect, fill)
            rect.set(x - 2.2f, y - 2.5f, x - 0.6f, y - 1.5f); c.drawRect(rect, fill)
            rect.set(x + 0.6f, y - 2.5f, x + 2.2f, y - 1.5f); c.drawRect(rect, fill)
            c.restore()
        }
    }

    private fun drawListenRings(c: Canvas, g: Float) {
        line.strokeWidth = 1.2f
        for (i in 0..2) {
            val q = ((g * 0.9f + i / 3f) % 1f)
            line.color = Color.argb(((1 - q) * 200).toInt(), 70, 160, 255)
            c.drawCircle(50f, 22f, 16f + q * 16f, line)
        }
    }

    private fun drawThinkDots(c: Canvas, g: Float) {
        fill.color = Color.WHITE; outline(0.8f)
        for (i in 0..2) {
            val r = 1.8f + i * 1.1f
            val yy = 12f - i * 4.5f + sin(g * 4f + i) * 1f
            c.drawCircle(70f + i * 6f, yy, r, fill); c.drawCircle(70f + i * 6f, yy, r, line)
        }
    }

    // ------------------------------------------------------------------ utilidades de dibujo

    private val tmp = RectF()

    /** Manchas de camuflaje recortadas a la forma dada. */
    private fun camo(c: Canvas, shape: Path) {
        c.save()
        c.clipPath(shape)
        for (b in camoBlobs) {
            fill.color = camo[b[4].toInt()]
            tmp.set(b[0] - b[2], b[1] - b[3], b[0] + b[2], b[1] + b[3])
            c.drawOval(tmp, fill)
        }
        c.restore()
    }

    private fun bolivia(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val s = h / 3f
        fill.color = Color.parseColor("#D52B1E"); c.drawRect(x, y, x + w, y + s, fill)
        fill.color = Color.parseColor("#F9E300"); c.drawRect(x, y + s, x + w, y + 2 * s, fill)
        fill.color = Color.parseColor("#007934"); c.drawRect(x, y + 2 * s, x + w, y + h, fill)
        fill.color = Color.parseColor("#7A5A2A"); c.drawCircle(x + w / 2, y + h / 2, s * 0.42f, fill)
        line.color = Color.parseColor("#4A3F2A"); line.strokeWidth = 0.4f; c.drawRect(x, y, x + w, y + h, line)
    }

    private fun webLines(c: Canvas, cx: Float, cy: Float, r: Float, col: Int, w: Float, n: Int) {
        line.color = col; line.strokeWidth = w
        for (i in 0 until n) {
            val a = (i * 2 * PI / n).toFloat()
            c.drawLine(cx, cy, cx + cos(a) * r * 1.6f, cy + sin(a) * r * 1.6f, line)
        }
        var rr = r / 4f
        while (rr < r * 1.5f) {
            path.reset()
            for (i in 0..n) {
                val a = (i * 2 * PI / n).toFloat()
                val px = cx + cos(a) * rr; val py = cy + sin(a) * rr
                if (i == 0) path.moveTo(px, py) else {
                    val am = ((i - 0.5f) * 2 * PI / n).toFloat()
                    path.quadTo(cx + cos(am) * rr * 0.82f, cy + sin(am) * rr * 0.82f, px, py)
                }
            }
            c.drawPath(path, line)
            rr += r / 4f
        }
        path.reset()
    }

    private fun spiderEmblem(c: Canvas, x: Float, y: Float, s: Float, col: Int) {
        fill.color = col; line.color = col; line.strokeWidth = 0.6f * s
        rect.set(x - 1.6f * s, y, x + 1.6f * s, y + 6f * s); c.drawOval(rect, fill)
        c.drawCircle(x, y - 0.4f * s, 1.2f * s, fill)
        for (d in intArrayOf(-1, 1)) {
            c.drawLine(x, y + 1.5f * s, x + d * 4.5f * s, y - 2.5f * s, line)
            c.drawLine(x, y + 2.3f * s, x + d * 5f * s, y + 1.5f * s, line)
            c.drawLine(x, y + 3.2f * s, x + d * 5f * s, y + 5.5f * s, line)
            c.drawLine(x, y + 4f * s, x + d * 4f * s, y + 9f * s, line)
        }
    }

    private fun zigzag(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, col: Int) {
        line.color = col; line.strokeWidth = 0.7f
        path.reset(); path.moveTo(x1, y1)
        val n = 5
        for (i in 1..n) {
            val k = i / n.toFloat()
            val off = if (i % 2 == 0) 1.6f else -1.6f
            path.lineTo(x1 + (x2 - x1) * k + (if (i < n) off else 0f), y1 + (y2 - y1) * k)
        }
        c.drawPath(path, line)
        path.reset()
    }
}
