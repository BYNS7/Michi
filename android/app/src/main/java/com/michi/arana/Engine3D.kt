package com.michi.arana

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Motor 3D mínimo sobre Canvas: mallas simples (cajas, cilindros, cúpulas), perspectiva,
 * luz con brillo de plástico y «estampados» (texturas o dibujos 2D) proyectados sobre las caras.
 * Se dibuja pieza por pieza de atrás hacia adelante (algoritmo del pintor).
 */
class Mat(val a: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)) {

    operator fun times(o: Mat): Mat {
        val r = FloatArray(12)
        val b = o.a
        for (i in 0..2) {
            val r0 = a[i * 4]; val r1 = a[i * 4 + 1]; val r2 = a[i * 4 + 2]
            r[i * 4] = r0 * b[0] + r1 * b[4] + r2 * b[8]
            r[i * 4 + 1] = r0 * b[1] + r1 * b[5] + r2 * b[9]
            r[i * 4 + 2] = r0 * b[2] + r1 * b[6] + r2 * b[10]
            r[i * 4 + 3] = r0 * b[3] + r1 * b[7] + r2 * b[11] + a[i * 4 + 3]
        }
        return Mat(r)
    }

    fun point(x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        a[0] * x + a[1] * y + a[2] * z + a[3],
        a[4] * x + a[5] * y + a[6] * z + a[7],
        a[8] * x + a[9] * y + a[10] * z + a[11]
    )

    /** Misma rotación, con el origen movido a [p]. */
    fun at(p: FloatArray) = Mat(a.copyOf().also { it[3] = p[0]; it[7] = p[1]; it[11] = p[2] })

    companion object {
        fun t(x: Float, y: Float, z: Float) = Mat(floatArrayOf(1f, 0f, 0f, x, 0f, 1f, 0f, y, 0f, 0f, 1f, z))
        fun s(x: Float, y: Float, z: Float) = Mat(floatArrayOf(x, 0f, 0f, 0f, 0f, y, 0f, 0f, 0f, 0f, z, 0f))
        fun rx(deg: Float): Mat { val r = deg * PI.toFloat() / 180f; val c = cos(r); val s = sin(r); return Mat(floatArrayOf(1f, 0f, 0f, 0f, 0f, c, -s, 0f, 0f, s, c, 0f)) }
        fun ry(deg: Float): Mat { val r = deg * PI.toFloat() / 180f; val c = cos(r); val s = sin(r); return Mat(floatArrayOf(c, 0f, s, 0f, 0f, 1f, 0f, 0f, -s, 0f, c, 0f)) }
        fun rz(deg: Float): Mat { val r = deg * PI.toFloat() / 180f; val c = cos(r); val s = sin(r); return Mat(floatArrayOf(c, -s, 0f, 0f, s, c, 0f, 0f, 0f, 0f, 1f, 0f)) }
    }
}

/** Estampado vectorial: se dibuja en coordenadas [src] (cuadrilátero de 4 puntos) y se proyecta sobre la cara. */
class Decal(val src: FloatArray, val clip: RectF? = null, val draw: (Canvas) -> Unit)

/** Textura de mapa de bits (camuflaje): [src] es el cuadrilátero en píxeles de la imagen. */
class Tex(val shader: BitmapShader, val src: FloatArray)

class Renderer {
    /** Distancia de la cámara y escala de la proyección (unidades de la figura → cuadrícula 0..100). */
    var camDist = 150f
    var zoom = 1.9f
    var centerX = 50f
    var centerY = 52f
    var view = Mat()

    private class Face(
        val sx: FloatArray, val n: Int, val z: Float, val color: Int, val shade: Float,
        val tex: Tex?, val decal: Decal?, val edge: Boolean
    )

    private class Part(val faces: ArrayList<Face>, var depth: Float)

    private val parts = ArrayList<Part>()
    private var cur: Part? = null
    private var partZSum = 0f
    private var partZCount = 0
    var model = Mat()

    private val light = norm(floatArrayOf(-0.45f, 0.65f, 0.62f))
    private val half = norm(floatArrayOf(light[0], light[1], light[2] + 1f))

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val seam = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 0.35f; strokeJoin = Paint.Join.ROUND }
    private val texPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val mx = Matrix()

    fun begin() { parts.clear() }

    /** Agrupa caras en una pieza; las piezas se ordenan por profundidad entre sí. */
    fun part(block: () -> Unit) {
        val p = Part(ArrayList(), 0f)
        cur = p; partZSum = 0f; partZCount = 0
        block()
        p.depth = if (partZCount > 0) partZSum / partZCount else -1e9f
        cur = null
        if (p.faces.isNotEmpty()) parts += p
    }

    /** Coordenadas de cámara de un punto en el espacio del modelo actual. */
    fun toCam(m: Mat, x: Float, y: Float, z: Float): FloatArray { val w = m.point(x, y, z); return view.point(w[0], w[1], w[2]) }

    fun project(c: FloatArray): FloatArray {
        val k = camDist * zoom / (camDist - c[2])
        return floatArrayOf(centerX + c[0] * k, centerY - c[1] * k)
    }

    /** Proyección directa de un punto del modelo actual a la pantalla. */
    fun screen(m: Mat, x: Float, y: Float, z: Float): FloatArray = project(toCam(m, x, y, z))

    /**
     * Añade una cara plana. [pts] en coordenadas locales (x,y,z consecutivos);
     * [center] = centro del sólido, para orientar la normal hacia fuera.
     */
    fun face(pts: FloatArray, center: FloatArray, color: Int, tex: Tex? = null, decal: Decal? = null, edge: Boolean = true) {
        val p = cur ?: return
        val n = pts.size / 3
        val cam = Array(n) { i -> toCam(model, pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2]) }
        val cc = toCam(model, center[0], center[1], center[2])
        var fx = 0f; var fy = 0f; var fz = 0f
        for (c in cam) { fx += c[0]; fy += c[1]; fz += c[2] }
        fx /= n; fy /= n; fz /= n
        partZSum += fz; partZCount++
        // Normal (Newell) orientada hacia fuera del sólido
        var nx = 0f; var ny = 0f; var nz = 0f
        for (i in 0 until n) {
            val a = cam[i]; val b = cam[(i + 1) % n]
            nx += (a[1] - b[1]) * (a[2] + b[2]); ny += (a[2] - b[2]) * (a[0] + b[0]); nz += (a[0] - b[0]) * (a[1] + b[1])
        }
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-6f) return
        nx /= len; ny /= len; nz /= len
        if (nx * (fx - cc[0]) + ny * (fy - cc[1]) + nz * (fz - cc[2]) < 0) { nx = -nx; ny = -ny; nz = -nz }
        // Visible si mira hacia la cámara
        if (nx * (0f - fx) + ny * (0f - fy) + nz * (camDist - fz) <= 0f) return
        val dif = max(0f, nx * light[0] + ny * light[1] + nz * light[2])
        val spec = max(0f, nx * half[0] + ny * half[1] + nz * half[2]).pow(24) * 0.35f
        val shade = (0.58f + 0.42f * dif)
        val sx = FloatArray(n * 2)
        for (i in 0 until n) { val s = project(cam[i]); sx[i * 2] = s[0]; sx[i * 2 + 1] = s[1] }
        p.faces += Face(sx, n, fz, lit(color, shade, spec), shade, tex, decal, edge)
    }

    fun draw(c: Canvas) {
        parts.sortBy { it.depth }
        for (p in parts) {
            p.faces.sortBy { it.z }
            for (f in p.faces) drawFace(c, f)
        }
    }

    private fun drawFace(c: Canvas, f: Face) {
        path.reset()
        path.moveTo(f.sx[0], f.sx[1])
        for (i in 1 until f.n) path.lineTo(f.sx[i * 2], f.sx[i * 2 + 1])
        path.close()
        if (f.tex != null && f.n >= 4) {
            mx.setPolyToPoly(f.tex.src, 0, f.sx, 0, 4)
            f.tex.shader.setLocalMatrix(mx)
            texPaint.shader = f.tex.shader
            c.drawPath(path, texPaint)
            fill.color = Color.argb(((1f - f.shade) * 230).toInt().coerceIn(0, 255), 0, 0, 0)
            c.drawPath(path, fill)
        } else {
            fill.color = f.color
            c.drawPath(path, fill)
        }
        if (f.decal != null && f.n >= 4) {
            c.save()
            c.clipPath(path)
            mx.setPolyToPoly(f.decal.src, 0, f.sx, 0, 4)
            c.concat(mx)
            f.decal.clip?.let { c.clipRect(it) }
            f.decal.draw(c)
            c.restore()
            // Sombra sobre el estampado para que siga la luz
            fill.color = Color.argb(((1f - f.shade) * 200).toInt().coerceIn(0, 255), 0, 0, 0)
            c.drawPath(path, fill)
        }
        seam.color = if (f.edge) darker(f.color, 0.72f) else f.color
        c.drawPath(path, seam)
    }

    // ------------------------------------------------------------- primitivas

    fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, color: Int,
            tex: Tex? = null, front: Decal? = null, sideTex: ((Int) -> Tex?)? = null) {
        val c = floatArrayOf((x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2)
        // frente (+z), atrás, derecha (+x), izquierda, arriba, abajo
        face(floatArrayOf(x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1), c, color, tex ?: sideTex?.invoke(0), front)
        face(floatArrayOf(x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0), c, color, tex ?: sideTex?.invoke(1))
        face(floatArrayOf(x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1), c, color, tex ?: sideTex?.invoke(2))
        face(floatArrayOf(x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0), c, color, tex ?: sideTex?.invoke(3))
        face(floatArrayOf(x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1), c, color, tex ?: sideTex?.invoke(4))
        face(floatArrayOf(x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0), c, color, tex ?: sideTex?.invoke(5))
    }

    /** Sólido de 8 esquinas: abajo (4) y arriba (4) en el orden: frente-izq, frente-der, atrás-der, atrás-izq. */
    fun hexa(bottom: FloatArray, top: FloatArray, color: Int, front: Decal? = null, sideTex: ((Int) -> Tex?)? = null) {
        val all = bottom + top
        val c = FloatArray(3).also { for (i in 0 until 8) for (k in 0..2) it[k] += all[i * 3 + k] / 8f }
        fun v(arr: FloatArray, i: Int) = floatArrayOf(arr[i * 3], arr[i * 3 + 1], arr[i * 3 + 2])
        fun q(a: FloatArray, b: FloatArray, cc: FloatArray, d: FloatArray) = a + b + cc + d
        face(q(v(top, 0), v(top, 1), v(bottom, 1), v(bottom, 0)), c, color, sideTex?.invoke(0), front)
        face(q(v(top, 2), v(top, 3), v(bottom, 3), v(bottom, 2)), c, color, sideTex?.invoke(1))
        face(q(v(top, 1), v(top, 2), v(bottom, 2), v(bottom, 1)), c, color, sideTex?.invoke(2))
        face(q(v(top, 3), v(top, 0), v(bottom, 0), v(bottom, 3)), c, color, sideTex?.invoke(3))
        face(q(v(top, 3), v(top, 2), v(top, 1), v(top, 0)), c, color, sideTex?.invoke(4))
        face(q(v(bottom, 0), v(bottom, 1), v(bottom, 2), v(bottom, 3)), c, color, sideTex?.invoke(5))
    }

    /**
     * Cilindro (o cono truncado) en el eje Y. Ángulo 0 = frente (+z), positivo hacia +x.
     * [decal] se reparte en franjas sobre los segmentos entre [decalFrom] y [decalTo] grados,
     * usando el rectángulo de origen [decalRect].
     */
    fun cylinder(r0: Float, r1: Float, y0: Float, y1: Float, segs: Int, color: Int,
                 caps: Boolean = true, topColor: Int = color,
                 decal: ((Canvas) -> Unit)? = null, decalRect: RectF? = null, decalFrom: Float = -90f, decalTo: Float = 90f,
                 sideTex: ((Int) -> Tex?)? = null, edge: Boolean = false) {
        val c = floatArrayOf(0f, (y0 + y1) / 2, 0f)
        val step = 360f / segs
        for (i in 0 until segs) {
            val a0 = -180f + i * step; val a1 = a0 + step
            val r = PI.toFloat() / 180f
            val s0 = sin(a0 * r); val c0 = cos(a0 * r); val s1 = sin(a1 * r); val c1 = cos(a1 * r)
            val pts = floatArrayOf(r1 * s0, y1, r1 * c0, r1 * s1, y1, r1 * c1, r0 * s1, y0, r0 * c1, r0 * s0, y0, r0 * c0)
            var d: Decal? = null
            if (decal != null && decalRect != null && a0 >= decalFrom - 0.01f && a1 <= decalTo + 0.01f) {
                val u0 = decalRect.left + (a0 - decalFrom) / (decalTo - decalFrom) * decalRect.width()
                val u1 = decalRect.left + (a1 - decalFrom) / (decalTo - decalFrom) * decalRect.width()
                d = Decal(floatArrayOf(u0, decalRect.top, u1, decalRect.top, u1, decalRect.bottom, u0, decalRect.bottom), decalRect, decal)
            }
            face(pts, c, color, sideTex?.invoke(i), d, edge)
        }
        if (caps) {
            val top = FloatArray(segs * 3); val bot = FloatArray(segs * 3)
            for (i in 0 until segs) {
                val a = (-180f + i * step) * PI.toFloat() / 180f
                top[i * 3] = r1 * sin(a); top[i * 3 + 1] = y1; top[i * 3 + 2] = r1 * cos(a)
                val j = segs - 1 - i
                val b = (-180f + j * step) * PI.toFloat() / 180f
                bot[i * 3] = r0 * sin(b); bot[i * 3 + 1] = y0; bot[i * 3 + 2] = r0 * cos(b)
            }
            if (r1 > 0.01f) face(top, c, topColor, edge = false)
            if (r0 > 0.01f) face(bot, c, color, edge = false)
        }
    }

    /** Cúpula (media esfera achatada) con base en y0 y radio horizontal r, alto h. */
    fun dome(r: Float, h: Float, y0: Float, color: Int, lat: Int = 4, lon: Int = 14, full: Boolean = false, sideTex: ((Int) -> Tex?)? = null) {
        val c = floatArrayOf(0f, y0 + (if (full) 0f else h * 0.4f), 0f)
        val from = if (full) -lat else 0
        for (i in from until lat) {
            val p0 = i / lat.toFloat() * (PI.toFloat() / 2); val p1 = (i + 1) / lat.toFloat() * (PI.toFloat() / 2)
            for (j in 0 until lon) {
                val t0 = j / lon.toFloat() * 2 * PI.toFloat(); val t1 = (j + 1) / lon.toFloat() * 2 * PI.toFloat()
                fun pt(p: Float, t: Float) = floatArrayOf(r * cos(p) * sin(t), y0 + h * sin(p), r * cos(p) * cos(t))
                val pts = pt(p0, t0) + pt(p0, t1) + pt(p1, t1) + pt(p1, t0)
                if (i == lat - 1) face(pt(p0, t0) + pt(p0, t1) + pt(p1, t1), c, color, sideTex?.invoke(i * lon + j), edge = false)
                else if (full && i == -lat) face(pt(p1, t1) + pt(p1, t0) + pt(p0, t0), c, color, edge = false)
                else face(pts, c, color, sideTex?.invoke(i * lon + j), edge = false)
            }
        }
        if (!full) {
            val base = FloatArray(lon * 3)
            for (j in 0 until lon) { val t = j / lon.toFloat() * 2 * PI.toFloat(); base[j * 3] = r * sin(t); base[j * 3 + 1] = y0; base[j * 3 + 2] = r * cos(t) }
            face(base, c, darker(color, 0.6f), edge = false)
        }
    }

    // ------------------------------------------------------------- utilidades

    companion object {
        fun norm(v: FloatArray): FloatArray { val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return floatArrayOf(v[0] / l, v[1] / l, v[2] / l) }

        fun lit(c: Int, k: Float, spec: Float): Int {
            fun ch(v: Int) = (v * k + 255 * spec).toInt().coerceIn(0, 255)
            return Color.argb(Color.alpha(c), ch(Color.red(c)), ch(Color.green(c)), ch(Color.blue(c)))
        }

        fun darker(c: Int, k: Float) = Color.argb(Color.alpha(c), (Color.red(c) * k).toInt(), (Color.green(c) * k).toInt(), (Color.blue(c) * k).toInt())

        fun shader(b: Bitmap) = BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }
}
