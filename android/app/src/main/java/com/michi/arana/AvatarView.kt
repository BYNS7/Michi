package com.michi.arana

import android.content.Context
import android.graphics.Bitmap
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
 * BYNS en 3D: minifigura de bloques modelada con mallas (cajas, cilindros y cúpulas) y articulada
 * como las de verdad: cabeza que gira, brazos que rotan en el hombro con codo, manos en C y piernas
 * que giran en la cadera. Se renderiza con [Renderer] (perspectiva, luz y estampados).
 *
 * Unidades del modelo ≈ milímetros de una minifigura (unos 41 de alto). El frente mira hacia +z.
 * Lado «N» = brazo/pierna derecho de la figura (x negativa), lado «P» = izquierdo (x positiva).
 */
class AvatarView(context: Context) : View(context) {

    enum class Skin(val title: String) {
        SOLDADO("BYNS Soldado"), ARANA_ROJA("BYNS Araña Roja"), ARANA_NEGRA("BYNS Araña Negra")
    }

    enum class Move {
        IDLE, WAVE, PARTY, LISTEN, THINK, TALK,
        AIM, KNEEL_AIM, SALUTE, ATTENTION, MARCH, WALK, RUN, CROUCH, SIT, PRONE, PUSHUPS, BINOCULARS, RADIO, FLEX, LOOK,
        DANCE, FLIP, SWING, SWING_TRAVEL, HANG, WEB, TRANSFORM
    }

    enum class Headgear(val label: String) { CASCO("casco"), CASCO_NVG("casco con visión nocturna"), BOINA("boina"), GORRA("gorra"), NINGUNO("sin casco") }
    enum class Camo(val label: String) { MULTICAM("multicam"), DESIERTO("desierto"), BOSQUE("bosque"), DIGITAL("digital"), NEGRO("negro") }
    enum class Eyewear(val label: String) { LENTES("lentes"), GAFAS("gafas tácticas"), NINGUNO("sin lentes") }
    enum class Weapon(val label: String) { FUSIL("fusil M4"), AK("AK-47"), FRANCOTIRADOR("rifle de francotirador"), PISTOLA("pistola") }

    data class Outfit(
        val head: Headgear = Headgear.CASCO,
        val camo: Camo = Camo.MULTICAM,
        val eyes: Eyewear = Eyewear.LENTES,
        val mochila: Boolean = true,
        val weapon: Weapon = Weapon.FUSIL
    )

    var skin = Skin.SOLDADO
        private set
    var move = Move.IDLE
        private set
    var outfit = Outfit()
        set(v) { field = v; invalidate() }
    var talking = false
        set(v) { field = v; invalidate() }
    /** Hacia qué lado está el centro de la pantalla: +1 derecha, -1 izquierda. */
    var facing = -1
        set(v) { field = if (v >= 0) 1 else -1; invalidate() }

    private var moveStart = 0L
    private var moveUntil = 0L
    private var pendingSkin: Skin? = null
    private var bricks: List<FloatArray> = emptyList()

    private val r3 = Renderer()

    // ------------------------------------------------------------------ colores
    private val ink = Color.parseColor("#1A1A22")
    private val yellow = Color.parseColor("#F6CB2F")
    private val spRed = Color.parseColor("#D9262B")
    private val spRedDark = Color.parseColor("#8E1418")
    private val spBlue = Color.parseColor("#2357C2")
    private val black = Color.parseColor("#1E1E24")
    private val navy = Color.parseColor("#1D3A7A")
    private val ice = Color.parseColor("#9FD8F5")
    private val iceHand = Color.parseColor("#8EC4F0")
    private val gunBlack = Color.parseColor("#2A2B2E")
    private val wood = Color.parseColor("#8A4B22")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val rect = RectF()

    fun setSkinNow(s: Skin) { skin = s; invalidate() }

    fun play(m: Move, ms: Long = 0) {
        move = m
        moveStart = SystemClock.uptimeMillis()
        moveUntil = if (ms > 0) moveStart + ms else 0
        invalidate()
    }

    /** Gira, suelta piezas y cambia de traje a mitad de la vuelta. */
    fun transformTo(s: Skin) {
        pendingSkin = s
        val r = Random(SystemClock.uptimeMillis())
        val cols = intArrayOf(spRed, spBlue, yellow, Color.parseColor("#B7996A"), black, ice, Color.WHITE)
        bricks = List(16) {
            val a = r.nextFloat() * 2 * PI.toFloat()
            floatArrayOf(cos(a) * (30 + r.nextFloat() * 40), sin(a) * (30 + r.nextFloat() * 30) - 40, cols[r.nextInt(cols.size)].toFloat(), r.nextFloat() * 360)
        }
        play(Move.TRANSFORM, 1200)
    }

    // ------------------------------------------------------------------ pose

    private class Pose {
        var x = 0f; var y = 0f; var z = 0f; var yaw = 0f; var pitch = 0f; var roll = 0f; var pivotY = 20f
        var hYaw = 0f; var hPitch = 0f; var hRoll = 0f
        var nX = 0f; var nY = 0f; var nZ = -6f; var nE = -30f
        var pX = 0f; var pY = 0f; var pZ = 6f; var pE = -30f
        var lN = 0f; var lP = 0f
        var hold = false; var binoc = false; var radio = false; var web = false
        var thread = 0 // 0 nada, 1 del casco/cabeza, 2 de los pies, 3 de la mano
    }

    private val p = Pose()

    private fun computePose(g: Float, t: Float) {
        p.x = 0f; p.y = 0f; p.z = 0f; p.pitch = 0f; p.roll = 0f; p.pivotY = 20f
        p.yaw = 22f * facing
        p.hYaw = 0f; p.hPitch = 0f; p.hRoll = 0f
        p.nX = 0f; p.nY = 0f; p.nZ = -6f; p.nE = -30f
        p.pX = 0f; p.pY = 0f; p.pZ = 6f; p.pE = -30f
        p.lN = 0f; p.lP = 0f
        p.hold = false; p.binoc = false; p.radio = false; p.web = false
        p.thread = if (skin != Skin.SOLDADO) 1 else 0
        val soldier = skin == Skin.SOLDADO
        val br = sin(g * 2f)
        when (move) {
            Move.IDLE, Move.TALK -> {
                p.y = br * 0.15f
                p.nX = br * 4f; p.pX = -br * 4f
                val s = sin(g * 0.55f); p.hYaw = 26f * s * s * s
            }
            Move.WAVE -> {
                p.pX = -165f; p.pZ = 22f + sin(t * 12f) * 18f; p.pE = -15f
                p.hRoll = 6f; p.hYaw = 8f * facing
            }
            Move.SALUTE -> {
                // Mano derecha a la frente
                p.nX = -162f; p.nY = -24f; p.nZ = -30f; p.nE = 0f
                p.pZ = 2f; p.pE = -8f; p.thread = 0
                p.yaw = 10f * facing
            }
            Move.ATTENTION -> {
                p.nZ = -1f; p.pZ = 1f; p.nE = -6f; p.pE = -6f
                p.y = if (t < 0.25f) sin(t / 0.25f * PI.toFloat()) * 1.5f else 0f
                p.thread = 0
            }
            Move.AIM -> {
                p.yaw = 62f * facing
                p.nX = -88f; p.nY = 14f; p.nZ = 0f; p.nE = -6f
                p.pX = -84f; p.pY = -34f; p.pZ = 0f; p.pE = -6f
                p.lN = -14f; p.lP = 14f; p.hPitch = 4f; p.hYaw = -6f
                p.hold = true; p.thread = 0
                if (t > 0.6f && (t % 1.5f) < 0.1f) p.z = -0.6f
            }
            Move.KNEEL_AIM -> {
                p.yaw = 62f * facing
                p.y = -5.2f; p.lN = -88f; p.lP = 58f
                p.nX = -88f; p.nY = 14f; p.nZ = 0f; p.nE = -6f
                p.pX = -84f; p.pY = -34f; p.pZ = 0f; p.pE = -6f
                p.hold = true; p.thread = 0
            }
            Move.MARCH, Move.WALK -> {
                val k = if (move == Move.MARCH) 42f else 28f
                val s = sin(t * (if (move == Move.MARCH) 6f else 7f))
                p.lN = s * k; p.lP = -s * k
                p.nX = -s * k * 1.1f; p.pX = s * k * 1.1f
                p.y = abs(cos(t * 7f)) * 0.5f
                p.thread = 0
            }
            Move.RUN -> {
                val s = sin(t * 12f)
                p.lN = s * 50f; p.lP = -s * 50f; p.nX = -s * 60f; p.pX = s * 60f; p.nE = -70f; p.pE = -70f
                p.pitch = 12f; p.y = abs(cos(t * 12f)) * 1.2f; p.thread = 0
            }
            Move.CROUCH -> {
                p.y = -4.5f; p.lN = -62f; p.lP = -62f; p.pitch = 10f
                p.nX = -45f; p.pX = -45f; p.hPitch = -8f; p.hYaw = sin(g) * 20f; p.thread = 0
            }
            Move.SIT -> {
                p.y = -8.6f; p.lN = -90f; p.lP = -90f
                p.nX = -25f; p.pX = -25f; p.thread = 0
            }
            Move.PRONE -> {
                p.yaw = 80f * facing; p.pitch = 90f; p.y = -15.5f
                p.hPitch = -55f
                p.nX = -160f; p.nY = 10f; p.nE = -10f; p.pX = -150f; p.pY = -20f; p.pE = -10f
                p.hold = soldier; p.thread = 0
            }
            Move.PUSHUPS -> {
                val s = (sin(t * 5f) + 1f) / 2f
                p.yaw = 80f * facing; p.pitch = 70f + s * 14f; p.y = -14f + (1 - s) * 2.5f
                p.nX = -78f + s * 10f; p.pX = -78f + s * 10f; p.nE = 0f; p.pE = 0f
                p.hPitch = -35f; p.thread = 0
            }
            Move.BINOCULARS -> {
                p.nX = -156f; p.nY = 90f; p.nZ = 48f; p.nE = 0f; p.pX = -156f; p.pY = -90f; p.pZ = -48f; p.pE = 0f
                p.hYaw = sin(t * 0.9f) * 35f; p.binoc = true; p.thread = 0
            }
            Move.RADIO -> {
                p.pX = -138f; p.pY = 48f; p.pZ = 6f; p.pE = -80f; p.hRoll = 9f; p.radio = true; p.thread = 0
            }
            Move.FLEX -> {
                p.nX = -90f; p.nY = -62f; p.nE = -100f; p.pX = -90f; p.pY = 62f; p.pE = -100f
                p.y = abs(sin(t * 3f)) * 0.6f; p.hPitch = -6f; p.thread = 0
            }
            Move.LOOK -> {
                p.hYaw = sin(t * 1.2f) * 40f; p.nX = -156f; p.nY = -18f; p.nZ = -36f; p.nE = -10f; p.thread = 0
            }
            Move.LISTEN -> { p.nX = -132f; p.nY = -24f; p.nZ = -6f; p.nE = -80f; p.hRoll = 10f }
            Move.THINK -> { p.pX = -96f; p.pY = -24f; p.pZ = 36f; p.pE = -50f; p.hPitch = 10f; p.hRoll = -6f }
            Move.PARTY -> {
                val j = abs(sin(t * 6f)); p.y = j * 6f
                p.nX = -170f; p.nZ = -28f; p.pX = -170f; p.pZ = 28f; p.nE = -10f; p.pE = -10f
                p.lN = -j * 18f; p.lP = j * 18f; p.thread = 0
            }
            Move.DANCE -> {
                val s = sin(t * 7f)
                p.yaw += sin(t * 3.5f) * 35f; p.roll = s * 7f; p.y = abs(s) * 1.2f
                p.nX = if (s > 0) -170f else -20f; p.pX = if (s > 0) -20f else -170f
                p.lN = if (s > 0) -25f else 0f; p.lP = if (s < 0) -25f else 0f
                p.hRoll = -s * 8f; p.thread = 0
            }
            Move.FLIP -> {
                val k = (t / 0.9f).coerceIn(0f, 1f)
                p.y = sin(PI.toFloat() * k) * 13f; p.pitch = -360f * k; p.pivotY = 22f
                p.nX = -170f; p.pX = -170f; p.lN = -60f; p.lP = -60f; p.thread = 0
            }
            Move.SWING, Move.SWING_TRAVEL -> {
                val s = sin(t * (if (move == Move.SWING) 2.6f else 3.4f))
                p.nX = -178f; p.nZ = 6f; p.nE = 0f
                p.pZ = 40f + s * 10f; p.pE = -20f
                p.lN = -30f + s * 20f; p.lP = 20f - s * 20f
                if (move == Move.SWING) { p.roll = s * 14f; p.pivotY = 41f }
                p.yaw = 10f * facing; p.thread = 3
            }
            Move.HANG -> {
                p.roll = 180f + sin(t * 1.6f) * 8f; p.pivotY = 21f; p.y = -5f
                p.nX = -176f + sin(t * 1.6f) * 6f; p.pX = -176f - sin(t * 1.6f) * 6f; p.nE = -10f; p.pE = -10f
                p.hPitch = 8f; p.thread = 2
            }
            Move.WEB -> {
                if (facing > 0) { p.pZ = 88f; p.pE = 0f; p.pX = -10f } else { p.nZ = -88f; p.nE = 0f; p.nX = -10f }
                p.yaw = 10f * facing; p.hYaw = 25f * facing; p.web = true; p.lN = -8f; p.lP = 8f
            }
            Move.TRANSFORM -> {
                val k = (t / 1.2f).coerceIn(0f, 1f)
                p.yaw += 720f * k * k; p.y = sin(PI.toFloat() * k) * 6f
                p.nX = -170f; p.nZ = -25f; p.pX = -170f; p.pZ = 25f; p.thread = 0
                if (k > 0.5f) pendingSkin?.let { skin = it }
            }
        }
        if (talking) { p.hRoll += sin(g * 9f) * 3f; p.hPitch += sin(g * 6f) * 2f }
    }

    // ------------------------------------------------------------------ dibujo

    private var handNScreen = floatArrayOf(0f, 0f)
    private var handPScreen = floatArrayOf(0f, 0f)
    private var headScreen = floatArrayOf(50f, 20f)
    private var feetScreen = floatArrayOf(50f, 90f)

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

        r3.view = Mat.rx(11f) * Mat.t(0f, -20.5f, 0f)
        r3.begin()
        val root = Mat.t(p.x, p.y, p.z) * Mat.t(0f, p.pivotY, 0f) * Mat.ry(p.yaw) * Mat.rx(p.pitch) * Mat.rz(p.roll) * Mat.t(0f, -p.pivotY, 0f)

        // Sombra en el suelo
        if (move != Move.HANG && move != Move.SWING && move != Move.SWING_TRAVEL) {
            val c = r3.screen(Mat(), p.x, 0f, p.z)
            val k = (1f - p.y / 30f).coerceIn(0.4f, 1.2f)
            fill.color = Color.argb(50, 0, 0, 0)
            rect.set(c[0] - 17f * k, c[1] - 3f * k, c[0] + 17f * k, c[1] + 3f * k); canvas.drawOval(rect, fill)
        }

        buildFigure(root, g)
        r3.draw(canvas)

        // Hilos de telaraña / cuerda
        when (p.thread) {
            1 -> threadLine(canvas, headScreen[0], headScreen[1])
            2 -> threadLine(canvas, feetScreen[0], feetScreen[1])
            3 -> { val h = if (p.nX < -100) handNScreen else handPScreen; threadLine(canvas, h[0], h[1]) }
        }
        if (p.web) {
            val h = if (facing > 0) handPScreen else handNScreen
            val k = (t / 0.3f).coerceIn(0f, 1f)
            val ex = h[0] + (if (facing > 0) 1 else -1) * 70f * k
            line.color = Color.WHITE; line.strokeWidth = 1.1f
            canvas.drawLine(h[0], h[1], ex, h[1] - 14f * k, line)
            if (k >= 1f) for (i in 0 until 6) {
                val a = i * PI.toFloat() / 3f
                canvas.drawLine(ex, h[1] - 14f, ex + cos(a) * 5f, h[1] - 14f + sin(a) * 5f, line)
            }
        }
        if (move == Move.TRANSFORM) drawBricks(canvas, t)
        if (move == Move.LISTEN) drawListenRings(canvas, g)
        if (move == Move.THINK) drawThinkDots(canvas, g)

        canvas.restore()
        if (isShown) postInvalidateDelayed(33)
    }

    /** Mano que sujeta la telaraña/cuerda, en píxeles de esta vista. */
    fun holdPoint(): FloatArray {
        val u = min(width, height) / 100f
        val h = if (p.nX < -100) handNScreen else handPScreen
        return floatArrayOf((width - 100 * u) / 2f + h[0] * u, (height - 100 * u) / 2f + h[1] * u)
    }

    private fun threadLine(c: Canvas, x: Float, y: Float) {
        val rope = skin == Skin.SOLDADO
        line.color = if (rope) Color.parseColor("#7A6040") else Color.argb(235, 255, 255, 255)
        line.strokeWidth = if (rope) 1.3f else 0.9f
        c.drawLine(x, -20f, x, y, line)
    }

    // ------------------------------------------------------------------ modelo

    private fun buildFigure(root: Mat, g: Float) {
        val soldier = skin == Skin.SOLDADO
        val legC = when (skin) { Skin.SOLDADO -> camoBase(); Skin.ARANA_ROJA -> spBlue; Skin.ARANA_NEGRA -> black }
        val bootC = when (skin) { Skin.SOLDADO -> bootColor(); Skin.ARANA_ROJA -> spRed; Skin.ARANA_NEGRA -> black }
        val camoTex: ((Int) -> Tex?)? = if (soldier) { i -> camoTex(i) } else null

        // Piernas
        for (side in intArrayOf(-1, 1)) {
            val leg = root * Mat.t(side * 3.9f, 12f, 0f) * Mat.rx(if (side < 0) p.lN else p.lP)
            r3.model = leg
            r3.part {
                r3.box(-3.75f, -8.4f, -3.2f, 3.75f, 0f, 3.2f, legC, sideTex = camoTex,
                    front = if (skin == Skin.ARANA_NEGRA) Decal(floatArrayOf(0f, 0f, 10f, 0f, 10f, 12f, 0f, 12f)) { c -> legPrint(c) } else null)
            }
            r3.part { r3.box(-3.75f, -12f, -3.2f, 3.75f, -8.4f, 4.6f, bootC) }
            if (soldier) {
                r3.part { r3.box(-2.6f, -7.6f, 3.2f, 2.6f, -4.4f, 4.1f, kneeColor()) }
                if (side < 0 && outfit.weapon == Weapon.PISTOLA && !p.hold) r3.part {
                    r3.box(-5.0f, -6.5f, -1.6f, -3.75f, -1.5f, 1.6f, gunBlack)
                    r3.box(-4.9f, -1.5f, -0.6f, -3.9f, 0.6f, 0.9f, gunBlack)
                }
            }
            r3.model = leg
            feetScreen = r3.screen(leg, 0f, -12f, 0.5f).let { if (side < 0) it else floatArrayOf((feetScreen[0] + it[0]) / 2, (feetScreen[1] + it[1]) / 2) }
        }

        // Cadera
        r3.model = root
        r3.part { r3.box(-7.8f, 12f, -3.6f, 7.8f, 15.2f, 3.6f, legC, sideTex = camoTex) }

        // Torso
        val torsoFront = Decal(floatArrayOf(37f, 37f, 63f, 37f, 69f, 64f, 31f, 64f)) { c -> torsoPrint(c) }
        r3.part {
            r3.hexa(
                floatArrayOf(-7.8f, 15.2f, 3.8f, 7.8f, 15.2f, 3.8f, 7.8f, 15.2f, -3.8f, -7.8f, 15.2f, -3.8f),
                floatArrayOf(-6f, 27.6f, 3.2f, 6f, 27.6f, 3.2f, 6f, 27.6f, -3.2f, -6f, 27.6f, -3.2f),
                torsoColor(), front = torsoFront, sideTex = camoTex
            )
        }
        if (soldier) gear(root)

        // Cabeza
        val head = root * Mat.t(0f, 27.6f, 0f) * Mat.ry(p.hYaw) * Mat.rx(p.hPitch) * Mat.rz(p.hRoll) * Mat.t(0f, -27.6f, 0f)
        r3.model = head
        val skinC = when (skin) { Skin.SOLDADO -> yellow; Skin.ARANA_ROJA -> spRed; Skin.ARANA_NEGRA -> black }
        r3.part {
            r3.cylinder(2.4f, 2.4f, 27.4f, 29.6f, 10, skinC)
        }
        r3.part {
            r3.cylinder(4.4f, 4.9f, 29.4f, 30.0f, 16, skinC, caps = false)
            r3.cylinder(4.9f, 4.9f, 30.0f, 38.6f, 16, skinC, caps = false, decal = { c -> facePrint(c, g) }, decalRect = RectF(37f, 9.5f, 63f, 34f))
            r3.cylinder(4.9f, 4.3f, 38.6f, 39.4f, 16, skinC)
        }
        headScreen = r3.screen(head, 0f, 41f, 0f)
        if (!(soldier && outfit.head != Headgear.NINGUNO)) r3.part { r3.cylinder(2.9f, 2.9f, 39.3f, 41f, 14, skinC) }
        if (soldier) headgear(head)
        if (p.binoc) r3.part {
            r3.model = head
            for (s in intArrayOf(-1, 1)) {
                r3.model = head * Mat.t(s * 1.7f, 33.6f, 4.6f) * Mat.rx(90f)
                r3.cylinder(1.25f, 1.25f, 0f, 4f, 10, gunBlack, topColor = Color.parseColor("#3A5A7A"))
            }
        }

        // Brazos
        arm(root, -1, if (soldier) camoBase() else if (skin == Skin.ARANA_ROJA) spBlue else black,
            if (soldier) yellow else if (skin == Skin.ARANA_ROJA) spRed else iceHand, camoTex)
        arm(root, 1, if (soldier) camoBase() else if (skin == Skin.ARANA_ROJA) spBlue else black,
            if (soldier) yellow else if (skin == Skin.ARANA_ROJA) spRed else iceHand, camoTex)

        // Arma
        if (soldier) {
            if (p.hold) {
                val hand = handPosN
                // Tumbado, el arma apunta hacia donde mira la cabeza
                val aim = if (move == Move.PRONE) Mat.rx(-90f) else Mat()
                weapon(root.at(hand) * aim * Mat.t(0f, 1.0f, 0.2f), outfit.weapon)
            } else if (outfit.weapon != Weapon.PISTOLA) {
                val back = root * Mat.t(0f, 21.5f, if (outfit.mochila) -10f else -5.0f) * Mat.rz(38f) * Mat.ry(90f) * Mat.t(0f, 0f, -2f)
                weapon(back, outfit.weapon)
            }
        }
    }

    private var handPosN = floatArrayOf(0f, 0f, 0f)

    private fun arm(root: Mat, side: Int, armC: Int, handC: Int, tex: ((Int) -> Tex?)?) {
        val n = side < 0
        val sh = root * Mat.t(side * 7.0f, 25.6f, 0f) *
            Mat.ry(if (n) p.nY else p.pY) * Mat.rz(if (n) p.nZ else p.pZ) * Mat.rx(if (n) p.nX else p.pX)
        val low = sh * Mat.t(0f, -5.2f, 0f) * Mat.rx(if (n) p.nE else p.pE)
        val hand = low * Mat.t(0f, -5.6f, 0f)
        r3.model = sh
        r3.part {
            r3.dome(2.15f, 2.15f, 0f, armC, lat = 3, lon = 10, full = true)
            r3.cylinder(1.95f, 2.1f, -5.4f, 0f, 10, armC, caps = false, sideTex = tex)
        }
        if (skin == Skin.SOLDADO && !n) {
            // Parche de Bolivia en el hombro (su cara frontal mira hacia fuera del brazo)
            r3.model = sh * Mat.ry(90f)
            r3.part {
                r3.box(-1.3f, -3.6f, 1.95f, 1.3f, -1.4f, 2.3f, Color.parseColor("#F9E300"),
                    front = Decal(floatArrayOf(0f, 0f, 9f, 0f, 9f, 6f, 0f, 6f)) { c -> bolivia(c, 0f, 0f, 9f, 6f) })
            }
        }
        r3.model = low
        r3.part { r3.cylinder(1.75f, 1.95f, -5.2f, 0.3f, 10, armC, sideTex = tex) }
        r3.model = hand * Mat.rx(90f)
        r3.part {
            r3.cylinder(2.15f, 2.15f, -1.3f, 1.3f, 12, handC)
            r3.cylinder(1.05f, 1.05f, 1.3f, 1.45f, 10, Renderer.darker(handC, 0.55f), caps = true)
            r3.cylinder(1.05f, 1.05f, -1.45f, -1.3f, 10, Renderer.darker(handC, 0.55f), caps = true)
        }
        // muñeca
        r3.model = low
        r3.part { r3.cylinder(0.9f, 0.9f, -6.0f, -4.9f, 8, handC) }
        val pos = hand.point(0f, 0f, 0f)
        if (n) handPosN = pos
        val scr = r3.screen(hand, 0f, 0f, 0f)
        if (n) handNScreen = scr else handPScreen = scr
        if (!n && p.radio) {
            r3.model = hand
            r3.part {
                r3.box(-0.9f, -0.5f, -2.0f, 0.9f, 3.6f, -0.6f, gunBlack)
                r3.cylinder(0.18f, 0.18f, 3.6f, 8.5f, 6, gunBlack)
            }
        }
    }

    /** Chaleco, mochila y correas del soldado. */
    private fun gear(root: Mat) {
        r3.model = root
        val vc = vestColor()
        r3.part {
            r3.box(-6.4f, 16.0f, 3.7f, 6.4f, 26.6f, 5.2f, vc, front = Decal(floatArrayOf(0f, 0f, 24f, 0f, 24f, 20f, 0f, 20f)) { c -> vestPrint(c, vc) })
        }
        r3.part {
            for (x in floatArrayOf(-4.6f, -0.7f, 3.2f)) r3.box(x, 16.2f, 5.2f, x + 2.6f, 19.8f, 6.3f, Renderer.darker(vc, 0.9f))
        }
        r3.part { r3.box(-6.2f, 16.2f, -5.2f, 6.2f, 26.4f, -3.7f, vc) }
        for (x in floatArrayOf(-5.6f, 3.4f)) r3.part { r3.box(x, 26.4f, -5.2f, x + 2.2f, 28.4f, 5.2f, vc) }
        if (outfit.mochila) {
            val pc = Renderer.darker(vc, 0.85f)
            r3.part { r3.box(-5.6f, 15.5f, -10.6f, 5.6f, 28.8f, -5.2f, pc) }
            r3.part { r3.box(-4.6f, 26.0f, -10.9f, 4.6f, 29.6f, -6.0f, Renderer.darker(pc, 0.9f)) }
            r3.part { r3.box(-5.0f, 16.5f, -11.6f, 5.0f, 22.5f, -10.6f, Renderer.darker(pc, 0.92f)) }
        }
    }

    private fun headgear(head: Mat) {
        r3.model = head
        when (outfit.head) {
            Headgear.CASCO, Headgear.CASCO_NVG -> {
                val hc = helmetColor()
                r3.part { r3.dome(5.75f, 4.4f, 36.9f, hc, lat = 4, lon = 16) }
                r3.part { r3.cylinder(5.95f, 5.95f, 36.1f, 37.1f, 16, Renderer.darker(hc, 0.85f), caps = false) }
                r3.part {
                    r3.box(-1.4f, 37.8f, 4.5f, 1.4f, 40.0f, 5.8f, Renderer.darker(hc, 0.75f))
                }
                for (s in intArrayOf(-1, 1)) r3.part { r3.box(if (s < 0) -6.6f else 5.6f, 36.8f, -2.2f, if (s < 0) -5.6f else 6.6f, 38.8f, 2.2f, Renderer.darker(hc, 0.75f)) }
                if (outfit.head == Headgear.CASCO_NVG) {
                    r3.part {
                        r3.model = head * Mat.t(0f, 39.0f, 5.4f) * Mat.rx(-35f)
                        r3.box(-2.9f, -0.6f, 0f, 2.9f, 1.4f, 2.4f, gunBlack)
                        for (s in intArrayOf(-1, 1)) {
                            r3.model = head * Mat.t(0f, 39.0f, 5.4f) * Mat.rx(-35f) * Mat.t(s * 1.6f, 0.4f, 2.2f) * Mat.rx(90f)
                            r3.cylinder(1.0f, 1.0f, 0f, 3.2f, 10, gunBlack, topColor = Color.parseColor("#3FAF5A"))
                        }
                    }
                }
            }
            Headgear.BOINA -> {
                r3.model = head * Mat.t(0f, 38.6f, 0f) * Mat.rz(-14f) * Mat.t(1.2f, 0f, 0f)
                r3.part { r3.dome(6.1f, 2.4f, 0f, Color.parseColor("#7A1F1F"), lat = 3, lon = 16) }
                r3.model = head
                r3.part { r3.cylinder(5.2f, 5.2f, 37.4f, 38.8f, 16, Color.parseColor("#4A1414"), caps = false) }
                r3.part { r3.box(1.6f, 38.0f, 4.9f, 3.0f, 39.8f, 5.6f, Color.parseColor("#E1B54A")) }
            }
            Headgear.GORRA -> {
                val gc = camoBase()
                r3.part { r3.dome(5.3f, 3.0f, 38.0f, gc, lat = 3, lon = 16, sideTex = { i -> camoTex(i) }) }
                r3.part { r3.cylinder(5.3f, 5.3f, 36.8f, 38.1f, 16, gc, caps = false, sideTex = { i -> camoTex(i) }) }
                r3.part { r3.box(-3.6f, 36.7f, 4.2f, 3.6f, 37.3f, 9.0f, Renderer.darker(gc, 0.85f)) }
            }
            Headgear.NINGUNO -> r3.part { r3.cylinder(2.9f, 2.9f, 39.3f, 41f, 14, yellow) }
        }
    }

    private fun weapon(m: Mat, w: Weapon) {
        r3.model = m
        val furn = if (outfit.camo == Camo.DESIERTO || outfit.camo == Camo.MULTICAM) Color.parseColor("#8C7148") else gunBlack
        when (w) {
            Weapon.FUSIL -> r3.part {
                r3.box(-0.7f, -0.3f, -3.2f, 0.7f, 2.0f, 6.0f, gunBlack)                 // cajón
                r3.box(-0.65f, -0.4f, -9.5f, 0.65f, 1.7f, -3.2f, furn)                  // culata
                r3.box(-0.85f, 0.0f, 6.0f, 0.85f, 1.9f, 11.0f, furn)                    // guardamanos
                r3.box(-0.55f, -4.2f, 1.6f, 0.55f, -0.3f, 3.6f, gunBlack)               // cargador
                r3.box(-0.5f, -2.8f, -1.4f, 0.5f, -0.3f, 0.2f, gunBlack)                // empuñadura
                r3.box(-0.6f, 2.0f, -0.4f, 0.6f, 3.3f, 3.2f, gunBlack)                  // mira
                r3.model = m * Mat.t(0f, 1.1f, 11f) * Mat.rx(90f)
                r3.cylinder(0.35f, 0.35f, 0f, 5.5f, 8, gunBlack)
            }
            Weapon.AK -> r3.part {
                r3.box(-0.7f, -0.3f, -3.2f, 0.7f, 1.8f, 5.5f, gunBlack)                 // cajón
                r3.hexa(
                    floatArrayOf(-0.6f, -1.8f, -10.5f, 0.6f, -1.8f, -10.5f, 0.6f, -1.8f, -3.2f, -0.6f, -1.8f, -3.2f).let { it },
                    floatArrayOf(-0.6f, 1.4f, -10.5f, 0.6f, 1.4f, -10.5f, 0.6f, 1.5f, -3.2f, -0.6f, 1.5f, -3.2f), wood)   // culata de madera
                r3.box(-0.8f, -0.1f, 5.5f, 0.8f, 1.6f, 10.0f, wood)                     // guardamanos
                r3.box(-0.5f, -2.9f, -1.5f, 0.5f, -0.3f, 0.1f, wood)                    // empuñadura
                // cargador curvo (tres tramos)
                r3.box(-0.55f, -2.5f, 1.3f, 0.55f, -0.3f, 3.6f, Color.parseColor("#B5541E"))
                r3.model = m * Mat.t(0f, -2.4f, 1.5f) * Mat.rx(-18f)
                r3.box(-0.55f, -2.4f, 0f, 0.55f, 0f, 2.3f, Color.parseColor("#B5541E"))
                r3.model = m * Mat.t(0f, -4.5f, 2.3f) * Mat.rx(-34f)
                r3.box(-0.55f, -2.0f, 0f, 0.55f, 0f, 2.3f, Color.parseColor("#B5541E"))
                r3.model = m * Mat.t(0f, 2.1f, 5.5f) * Mat.rx(90f)
                r3.cylinder(0.42f, 0.42f, 0f, 4.5f, 8, gunBlack)                         // tubo de gases
                r3.model = m * Mat.t(0f, 0.9f, 10f) * Mat.rx(90f)
                r3.cylinder(0.33f, 0.33f, 0f, 6.5f, 8, gunBlack)                         // cañón
                r3.model = m
                r3.box(-0.35f, 0.9f, 14.6f, 0.35f, 2.6f, 15.2f, gunBlack)               // punto de mira
            }
            Weapon.FRANCOTIRADOR -> r3.part {
                r3.box(-0.75f, -0.4f, -11f, 0.75f, 1.8f, 8f, Color.parseColor("#4B5320"))
                r3.box(-0.5f, -2.8f, -1.4f, 0.5f, -0.4f, 0.2f, gunBlack)
                r3.box(-0.5f, -2.6f, 1.6f, 0.5f, -0.4f, 3.2f, gunBlack)
                r3.model = m * Mat.t(0f, 3.3f, -2.5f) * Mat.rx(90f)
                r3.cylinder(0.95f, 0.95f, 0f, 8f, 10, gunBlack, topColor = Color.parseColor("#3A5A7A"))
                r3.model = m * Mat.t(0f, 1.0f, 8f) * Mat.rx(90f)
                r3.cylinder(0.35f, 0.35f, 0f, 11f, 8, gunBlack)
                for (s in intArrayOf(-1, 1)) {
                    r3.model = m * Mat.t(s * 0.6f, -0.2f, 10f) * Mat.rz(s * 18f)
                    r3.box(-0.2f, -4.5f, -0.2f, 0.2f, 0f, 0.2f, gunBlack)
                }
            }
            Weapon.PISTOLA -> r3.part {
                r3.box(-0.55f, 0f, -1.2f, 0.55f, 1.4f, 5f, gunBlack)
                r3.model = m * Mat.rx(15f)
                r3.box(-0.5f, -3f, -1.3f, 0.5f, 0f, 0.6f, gunBlack)
            }
        }
    }

    // ------------------------------------------------------------------ estampados (dibujos 2D)

    private fun facePrint(c: Canvas, g: Float) {
        val lx = 0f
        val blink = (g % 4.3f) > 4.15f
        when (skin) {
            Skin.SOLDADO -> soldierFace(c, lx, blink, g)
            Skin.ARANA_ROJA -> spiderFace(c, lx, blink, true, g)
            Skin.ARANA_NEGRA -> spiderFace(c, lx, blink, false, g)
        }
    }

    private fun soldierFace(c: Canvas, lx: Float, blink: Boolean, g: Float) {
        line.color = ink; line.strokeWidth = 1.5f
        c.drawLine(40.5f + lx, 20.2f, 47.5f + lx, 21.3f, line)
        c.drawLine(59.5f + lx, 20.2f, 52.5f + lx, 21.3f, line)
        when (outfit.eyes) {
            Eyewear.GAFAS -> {
                fill.color = Color.parseColor("#2A2A30")
                rect.set(38.5f, 21.6f, 61.5f, 28.2f); c.drawRoundRect(rect, 2.5f, 2.5f, fill)
                fill.color = Color.argb(150, 255, 150, 40)
                rect.set(40f, 22.4f, 49f, 27.2f); c.drawRoundRect(rect, 2f, 2f, fill)
                rect.set(51f, 22.4f, 60f, 27.2f); c.drawRoundRect(rect, 2f, 2f, fill)
                fill.color = Color.argb(160, 255, 255, 255)
                c.drawCircle(42.5f, 23.6f, 0.7f, fill); c.drawCircle(53.5f, 23.6f, 0.7f, fill)
            }
            else -> {
                if (blink) {
                    line.strokeWidth = 0.9f
                    c.drawLine(42.8f, 25f, 45.6f, 25f, line); c.drawLine(54.4f, 25f, 57.2f, 25f, line)
                } else {
                    fill.color = ink
                    c.drawCircle(44.2f, 24.9f, 1.25f, fill); c.drawCircle(55.8f, 24.9f, 1.25f, fill)
                    fill.color = Color.WHITE
                    c.drawCircle(44.6f, 24.4f, 0.4f, fill); c.drawCircle(56.2f, 24.4f, 0.4f, fill)
                }
                if (outfit.eyes == Eyewear.LENTES) {
                    line.strokeWidth = 1f
                    rect.set(39.8f, 22.2f, 48.6f, 27.6f); c.drawRoundRect(rect, 1.2f, 1.2f, line)
                    rect.set(51.4f, 22.2f, 60.2f, 27.6f); c.drawRoundRect(rect, 1.2f, 1.2f, line)
                    c.drawLine(48.6f, 23.8f, 51.4f, 23.8f, line)
                    c.drawLine(39.8f, 23.5f, 37.2f, 23f, line); c.drawLine(60.2f, 23.5f, 62.8f, 23f, line)
                }
            }
        }
        if (talking) {
            val open = 0.8f + abs(sin(g * 13f)) * 1.8f
            fill.color = Color.parseColor("#5A2A1A")
            rect.set(47f, 30.5f - open / 2, 53f, 30.5f + open / 2); c.drawOval(rect, fill)
        } else {
            line.color = ink; line.strokeWidth = 0.9f
            path.reset(); path.moveTo(46.5f, 30.8f); path.quadTo(51f, 31.4f, 54f, 30f); c.drawPath(path, line)
        }
        if (outfit.head == Headgear.CASCO || outfit.head == Headgear.CASCO_NVG) {
            line.color = Color.parseColor("#3E3A30"); line.strokeWidth = 0.9f
            c.drawLine(37.6f, 18f, 38.6f, 31f, line); c.drawLine(62.4f, 18f, 61.4f, 31f, line)
        }
    }

    private fun spiderFace(c: Canvas, lx: Float, blink: Boolean, red: Boolean, g: Float) {
        if (red) webLines(c, 50f, 22f, 30f, spRedDark, 0.55f, 12)
        else {
            fill.color = ice
            for (i in 0..4) for (j in 0..2) {
                c.drawCircle(41f + i * 1.6f, 28f + j * 1.6f + (i % 2) * 0.8f, 0.45f, fill)
                c.drawCircle(53f + i * 1.6f, 28f + j * 1.6f + (i % 2) * 0.8f, 0.45f, fill)
            }
            zigzag(c, 37.5f, 14f, 42f, 30f, ice); zigzag(c, 62.5f, 13f, 58f, 30f, ice)
        }
        for (s in intArrayOf(-1, 1)) {
            c.save()
            c.translate(50f + s * 6.6f + lx, 21.5f)
            c.scale(s.toFloat(), if (blink) 0.15f else if (talking) 0.8f + abs(sin(g * 10f)) * 0.2f else 1f)
            path.reset()
            path.moveTo(-4.8f, 3.8f); path.quadTo(-4.5f, -3.2f, 1.5f, -4.4f); path.quadTo(6.6f, -4.6f, 6.4f, 0.8f)
            path.quadTo(6f, 5.8f, 0.5f, 5.8f); path.quadTo(-3.5f, 5.8f, -4.8f, 3.8f); path.close()
            fill.color = Color.WHITE; c.drawPath(path, fill)
            if (!red) { line.color = spRed; line.strokeWidth = 2.6f; c.drawPath(path, line) }
            line.color = ink; line.strokeWidth = if (red) 1.9f else 0.9f; c.drawPath(path, line)
            c.restore()
        }
    }

    private fun torsoPrint(c: Canvas) {
        when (skin) {
            Skin.SOLDADO -> {
                camoFill(c, RectF(28f, 34f, 72f, 66f))
                fill.color = Color.parseColor("#4F5E33")
                path.reset(); path.moveTo(45f, 37f); path.lineTo(55f, 37f); path.lineTo(50f, 42f); path.close(); c.drawPath(path, fill)
            }
            Skin.ARANA_ROJA -> {
                fill.color = spBlue; c.drawRect(28f, 34f, 72f, 66f, fill)
                val center = Path().apply { moveTo(41f, 37f); lineTo(59f, 37f); lineTo(56f, 64f); lineTo(44f, 64f); close() }
                fill.color = spRed; c.drawPath(center, fill)
                c.save(); c.clipPath(center); webLines(c, 50f, 36f, 32f, spRedDark, 0.5f, 9); c.restore()
                spiderEmblem(c, 50f, 45f, 1f, ink)
                line.color = Color.argb(70, 0, 0, 0); line.strokeWidth = 0.6f; c.drawLine(30f, 60f, 70f, 60f, line)
            }
            Skin.ARANA_NEGRA -> {
                fill.color = black; c.drawRect(28f, 34f, 72f, 66f, fill)
                fill.color = navy
                path.reset(); path.moveTo(40f, 52f); path.lineTo(52f, 47f); path.lineTo(62f, 58f); path.lineTo(48f, 65f); path.close(); c.drawPath(path, fill)
                zigzag(c, 33f, 40f, 44f, 62f, ice); zigzag(c, 66f, 39f, 58f, 63f, ice)
                spiderEmblem(c, 50f, 48f, 1.5f, spRed)
            }
        }
    }

    private fun legPrint(c: Canvas) {
        fill.color = navy
        path.reset(); path.moveTo(0f, 2f); path.lineTo(6f, 5f); path.lineTo(3f, 10f); path.lineTo(0f, 9f); path.close(); c.drawPath(path, fill)
        zigzag(c, 8.5f, 1f, 6f, 11f, ice)
    }

    private fun vestPrint(c: Canvas, vc: Int) {
        fill.color = Renderer.darker(vc, 0.72f)
        var yy = 9.5f
        while (yy < 19f) {
            var xx = 2f
            while (xx < 22f) { rect.set(xx, yy, xx + 3.2f, yy + 1.1f); c.drawRoundRect(rect, 0.5f, 0.5f, fill); xx += 4.4f }
            yy += 2.6f
        }
        bolivia(c, 7.5f, 1.8f, 9f, 5.6f)
    }

    // ------------------------------------------------------------------ camuflajes

    private val camoCache = HashMap<Camo, android.graphics.BitmapShader>()

    private fun camoColors(): IntArray = when (outfit.camo) {
        Camo.MULTICAM -> intArrayOf(0xFF8F8A62.toInt(), 0xFF5C6A3A.toInt(), 0xFF6E5537.toInt(), 0xFFC2B183.toInt(), 0xFF4A4F2E.toInt())
        Camo.DESIERTO -> intArrayOf(0xFFC8B48A.toInt(), 0xFFA88E62.toInt(), 0xFF8C7350.toInt(), 0xFFE0D2AE.toInt(), 0xFFB9A273.toInt())
        Camo.BOSQUE -> intArrayOf(0xFF6B7B4B.toInt(), 0xFF3E4A2A.toInt(), 0xFF8A6A45.toInt(), 0xFF1F1F1A.toInt(), 0xFF56663A.toInt())
        Camo.DIGITAL -> intArrayOf(0xFF7D8466.toInt(), 0xFF5A5F45.toInt(), 0xFFA9A98A.toInt(), 0xFF3F4433.toInt(), 0xFF8E9374.toInt())
        Camo.NEGRO -> intArrayOf(0xFF26272B.toInt(), 0xFF3A3B40.toInt(), 0xFF1A1A1E.toInt(), 0xFF4A4B52.toInt(), 0xFF303136.toInt())
    }

    private fun camoBase() = camoColors()[0]
    private fun vestColor() = when (outfit.camo) {
        Camo.MULTICAM, Camo.DESIERTO -> Color.parseColor("#B7996A")
        Camo.BOSQUE, Camo.DIGITAL -> Color.parseColor("#5E6B3A")
        Camo.NEGRO -> Color.parseColor("#2A2B30")
    }
    private fun helmetColor() = if (outfit.camo == Camo.NEGRO) Color.parseColor("#2E2F34") else if (outfit.camo == Camo.BOSQUE || outfit.camo == Camo.DIGITAL) Color.parseColor("#5C6440") else Color.parseColor("#B7996A")
    private fun bootColor() = if (outfit.camo == Camo.NEGRO || outfit.camo == Camo.BOSQUE) Color.parseColor("#2A2622") else Color.parseColor("#B7996A")
    private fun kneeColor() = vestColor()
    private fun torsoColor() = when (skin) { Skin.SOLDADO -> camoBase(); Skin.ARANA_ROJA -> spBlue; Skin.ARANA_NEGRA -> black }

    private fun camoShader(): android.graphics.BitmapShader = camoCache.getOrPut(outfit.camo) {
        val b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val cols = camoColors()
        cv.drawColor(cols[0])
        val r = Random(11)
        val pp = Paint(Paint.ANTI_ALIAS_FLAG)
        if (outfit.camo == Camo.DIGITAL) {
            for (i in 0 until 420) {
                pp.color = cols[1 + r.nextInt(4)]
                val x = r.nextInt(32) * 4f; val y = r.nextInt(32) * 4f
                cv.drawRect(x, y, x + 4f * (1 + r.nextInt(2)), y + 4f, pp)
            }
        } else {
            for (i in 0 until 90) {
                pp.color = cols[1 + r.nextInt(4)]
                val x = r.nextFloat() * 128; val y = r.nextFloat() * 128
                val rx = 5f + r.nextFloat() * 9f; val ry = 4f + r.nextFloat() * 6f
                cv.drawOval(x - rx, y - ry, x + rx, y + ry, pp)
            }
        }
        Renderer.shader(b)
    }

    private fun camoTex(i: Int): Tex {
        val ox = (i * 37 % 80).toFloat(); val oy = (i * 53 % 80).toFloat()
        return Tex(camoShader(), floatArrayOf(ox, oy, ox + 40f, oy, ox + 40f, oy + 40f, ox, oy + 40f))
    }

    /** Relleno de camuflaje para estampados vectoriales. */
    private fun camoFill(c: Canvas, area: RectF) {
        fill.shader = camoShader().also { it.setLocalMatrix(android.graphics.Matrix().apply { setScale(0.35f, 0.35f) }) }
        c.drawRect(area, fill)
        fill.shader = null
    }

    // ------------------------------------------------------------------ superposiciones 2D

    private fun drawBricks(c: Canvas, t: Float) {
        val k = (t / 1.2f).coerceIn(0f, 1f)
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
            c.drawCircle(headScreen[0], headScreen[1] + 12f, 14f + q * 16f, line)
        }
    }

    private fun drawThinkDots(c: Canvas, g: Float) {
        fill.color = Color.WHITE; line.color = ink; line.strokeWidth = 0.8f
        for (i in 0..2) {
            val r = 1.8f + i * 1.1f
            val x = headScreen[0] + 14f + i * 6f
            val yy = headScreen[1] - 2f - i * 4.5f + sin(g * 4f + i) * 1f
            c.drawCircle(x, yy, r, fill); c.drawCircle(x, yy, r, line)
        }
    }

    // ------------------------------------------------------------------ utilidades 2D

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
