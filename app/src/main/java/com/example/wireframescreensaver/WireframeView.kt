package com.example.wireframescreensaver

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.*

/**
 * A custom View that renders a slowly rotating 3D wireframe shape on a black background,
 * with a digital clock in the centre.
 *
 * Every 5–30 s the current shape shrinks to nothing, a new random shape is chosen,
 * and it grows back to full size – then the cycle repeats.
 */
class WireframeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ───────────────────────────── data types ─────────────────────────────

    private data class Vec3(val x: Float, val y: Float, val z: Float)
    private data class Shape(
        val name: String,
        val vertices: List<Vec3>,
        val edges: List<Pair<Int, Int>>
    )

    // ───────────────────────────── paint objects ──────────────────────────

    private val wirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.12f
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
        letterSpacing = 0.12f
        maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
    }

    // ───────────────────────────── rotation state ─────────────────────────

    // Rotation angles (radians), incremented every frame
    private var rotX = 0f
    private var rotY = 0f
    private var rotZ = 0f

    // Slightly different speeds on each axis for organic, non-repeating motion
    private val speedX = 0.0040f
    private val speedY = 0.0065f
    private val speedZ = 0.0028f

    // ───────────────────────────── scale / transition state ───────────────

    private var shapeScale = 1f          // 0.0 → invisible, 1.0 → full size

    private enum class AnimState { IDLE, SHRINKING, GROWING }

    private var animState = AnimState.IDLE
    private val shrinkSpeed = 0.012f     // fraction of full scale per frame (~60 fps → ~1.4 s)
    private val growSpeed  = 0.012f

    private var idleAccumMs  = 0L
    private var nextDelayMs  = randomDelay()

    // ───────────────────────────── shapes ─────────────────────────────────

    private val shapes: List<Shape> by lazy { buildAllShapes() }
    private var currentIdx = 0

    // ───────────────────────────── timing ─────────────────────────────────

    private var lastFrameMs = System.currentTimeMillis()

    // ══════════════════════════════════════════════════════════════════════
    //  Main draw loop
    // ══════════════════════════════════════════════════════════════════════

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // ── delta time ────────────────────────────────────────────────────
        val now  = System.currentTimeMillis()
        val dtMs = (now - lastFrameMs).coerceIn(1L, 50L)
        lastFrameMs = now

        // ── clear ─────────────────────────────────────────────────────────
        canvas.drawColor(Color.BLACK)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val minDim = min(w, h)

        // ── update animation state ────────────────────────────────────────
        updateAnimation(dtMs)

        // ── update rotation ───────────────────────────────────────────────
        rotX += speedX
        rotY += speedY
        rotZ += speedZ

        // ── draw wireframe ────────────────────────────────────────────────
        wirePaint.strokeWidth = minDim * 0.004f
        wirePaint.alpha = (shapeScale * 255f).toInt().coerceIn(0, 255)

        val displayRadius = minDim * 0.38f * shapeScale
        drawShape(canvas, shapes[currentIdx], cx, cy, displayRadius)

        // ── draw clock ────────────────────────────────────────────────────
        val clockSize = minDim * 0.095f
        clockPaint.textSize = clockSize
        glowPaint.textSize  = clockSize

        val timeStr = currentTimeString()
        val textY   = cy + clockSize * 0.35f

        // subtle glow layer behind the sharp text
        canvas.drawText(timeStr, cx, textY, glowPaint)
        canvas.drawText(timeStr, cx, textY, clockPaint)

        // ── schedule next frame ───────────────────────────────────────────
        postInvalidateOnAnimation()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Animation state machine
    // ══════════════════════════════════════════════════════════════════════

    private fun updateAnimation(dtMs: Long) {
        when (animState) {
            AnimState.IDLE -> {
                idleAccumMs += dtMs
                if (idleAccumMs >= nextDelayMs) {
                    animState    = AnimState.SHRINKING
                    idleAccumMs  = 0L
                }
            }
            AnimState.SHRINKING -> {
                shapeScale -= shrinkSpeed
                if (shapeScale <= 0f) {
                    shapeScale   = 0f
                    currentIdx   = pickNextShape()
                    animState    = AnimState.GROWING
                }
            }
            AnimState.GROWING -> {
                shapeScale += growSpeed
                if (shapeScale >= 1f) {
                    shapeScale   = 1f
                    animState    = AnimState.IDLE
                    nextDelayMs  = randomDelay()
                    idleAccumMs  = 0L
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  3-D rendering
    // ══════════════════════════════════════════════════════════════════════

    private fun drawShape(
        canvas: Canvas,
        shape: Shape,
        cx: Float,
        cy: Float,
        radius: Float
    ) {
        if (radius < 1f) return

        // Project each vertex
        val pts = Array(shape.vertices.size) { i ->
            project(transform(shape.vertices[i]), cx, cy, radius)
        }

        // Draw each edge
        for ((a, b) in shape.edges) {
            canvas.drawLine(pts[a].x, pts[a].y, pts[b].x, pts[b].y, wirePaint)
        }
    }

    /** Apply X → Y → Z rotation */
    private fun transform(v: Vec3): Vec3 =
        rotateX(rotateY(rotateZ(v, rotZ), rotY), rotX)

    /** Simple perspective projection onto the canvas */
    private fun project(v: Vec3, cx: Float, cy: Float, radius: Float): PointF {
        val camDist = 2.8f                         // camera distance from origin
        val z = v.z + camDist
        val factor = if (z > 0.01f) camDist / z else camDist / 0.01f
        return PointF(
            cx + v.x * factor * radius,
            cy + v.y * factor * radius
        )
    }

    // ── rotation helpers ──────────────────────────────────────────────────

    private fun rotateX(v: Vec3, a: Float): Vec3 {
        val c = cos(a); val s = sin(a)
        return Vec3(v.x, v.y * c - v.z * s, v.y * s + v.z * c)
    }

    private fun rotateY(v: Vec3, a: Float): Vec3 {
        val c = cos(a); val s = sin(a)
        return Vec3(v.x * c + v.z * s, v.y, -v.x * s + v.z * c)
    }

    private fun rotateZ(v: Vec3, a: Float): Vec3 {
        val c = cos(a); val s = sin(a)
        return Vec3(v.x * c - v.y * s, v.x * s + v.y * c, v.z)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Shape definitions  (vertices normalised to ≈ unit radius)
    // ══════════════════════════════════════════════════════════════════════

    private fun buildAllShapes(): List<Shape> = listOf(
        makeCube(),
        makePyramid(),
        makeTriangularPrism(),
        makeOctahedron(),
        makeTetrahedron(),
        makeIcosahedron(),
        makeDodecahedron(),
        makeHexagonalPrism(),
        makeDiamond()
    )

    /** Pick the next shape index, never the same as the current one */
    private fun pickNextShape(): Int {
        val candidates = shapes.indices.filter { it != currentIdx }
        return candidates.random()
    }

    // ─── Cube ─────────────────────────────────────────────────────────────
    private fun makeCube(): Shape {
        val s = 0.5f
        return Shape(
            name = "Cube",
            vertices = listOf(
                Vec3(-s,-s,-s), Vec3( s,-s,-s), Vec3( s, s,-s), Vec3(-s, s,-s),
                Vec3(-s,-s, s), Vec3( s,-s, s), Vec3( s, s, s), Vec3(-s, s, s)
            ),
            edges = listOf(
                // back face
                0 to 1, 1 to 2, 2 to 3, 3 to 0,
                // front face
                4 to 5, 5 to 6, 6 to 7, 7 to 4,
                // connecting pillars
                0 to 4, 1 to 5, 2 to 6, 3 to 7
            )
        )
    }

    // ─── Square Pyramid ───────────────────────────────────────────────────
    private fun makePyramid(): Shape {
        val s = 0.55f
        return Shape(
            name = "Pyramid",
            vertices = listOf(
                Vec3(-s,-s,-s), Vec3( s,-s,-s), Vec3( s,-s, s), Vec3(-s,-s, s),
                Vec3( 0f, s, 0f)                                              // apex
            ),
            edges = listOf(
                0 to 1, 1 to 2, 2 to 3, 3 to 0,    // base
                0 to 4, 1 to 4, 2 to 4, 3 to 4     // sides
            )
        )
    }

    // ─── Triangular Prism ─────────────────────────────────────────────────
    private fun makeTriangularPrism(): Shape {
        val r = 0.6f; val h = 0.5f
        // equilateral triangle cross-section
        val angles = listOf(90f, 210f, 330f).map { Math.toRadians(it.toDouble()) }
        val front  = angles.map { Vec3(cos(it).toFloat() * r, sin(it).toFloat() * r, -h) }
        val back   = angles.map { Vec3(cos(it).toFloat() * r, sin(it).toFloat() * r,  h) }
        val verts  = front + back
        return Shape(
            name = "Prism",
            vertices = verts,
            edges = listOf(
                0 to 1, 1 to 2, 2 to 0,    // front triangle
                3 to 4, 4 to 5, 5 to 3,    // back triangle
                0 to 3, 1 to 4, 2 to 5     // lateral edges
            )
        )
    }

    // ─── Octahedron ───────────────────────────────────────────────────────
    private fun makeOctahedron(): Shape {
        val a = 0.7f
        return Shape(
            name = "Octahedron",
            vertices = listOf(
                Vec3( 0f,  a, 0f),   // 0 top
                Vec3( 0f, -a, 0f),   // 1 bottom
                Vec3( a,  0f, 0f),   // 2 right
                Vec3(-a,  0f, 0f),   // 3 left
                Vec3( 0f, 0f,  a),   // 4 front
                Vec3( 0f, 0f, -a)    // 5 back
            ),
            edges = listOf(
                // upper half
                0 to 2, 0 to 3, 0 to 4, 0 to 5,
                // lower half
                1 to 2, 1 to 3, 1 to 4, 1 to 5,
                // equatorial belt
                2 to 4, 4 to 3, 3 to 5, 5 to 2
            )
        )
    }

    // ─── Tetrahedron ──────────────────────────────────────────────────────
    private fun makeTetrahedron(): Shape {
        // Regular tetrahedron inscribed in unit sphere
        val s = 0.82f
        return Shape(
            name = "Tetrahedron",
            vertices = listOf(
                Vec3( s,  s,  s),
                Vec3(-s, -s,  s),
                Vec3(-s,  s, -s),
                Vec3( s, -s, -s)
            ),
            edges = listOf(
                0 to 1, 0 to 2, 0 to 3,
                1 to 2, 1 to 3, 2 to 3
            )
        )
    }

    // ─── Icosahedron (low-poly sphere) ────────────────────────────────────
    private fun makeIcosahedron(): Shape {
        val phi = (1f + sqrt(5f)) / 2f
        val n   = sqrt(1f + phi * phi)
        val a   = 1f / n
        val b   = phi / n

        val verts = listOf(
            Vec3(  0f,  a,  b), Vec3(  0f, -a,  b),
            Vec3(  0f,  a, -b), Vec3(  0f, -a, -b),
            Vec3(  a,  b,  0f), Vec3( -a,  b,  0f),
            Vec3(  a, -b,  0f), Vec3( -a, -b,  0f),
            Vec3(  b,  0f,  a), Vec3( -b,  0f,  a),
            Vec3(  b,  0f, -a), Vec3( -b,  0f, -a)
        )

        // Edges connect vertex pairs whose distance equals the icosahedron edge length
        val edgeLen = dist3(verts[0], verts[1])
        val edges   = mutableListOf<Pair<Int, Int>>()
        for (i in verts.indices) {
            for (j in i + 1 until verts.size) {
                if (abs(dist3(verts[i], verts[j]) - edgeLen) < 0.001f) {
                    edges += i to j
                }
            }
        }
        return Shape("Icosahedron", verts, edges)
    }

    // ─── Dodecahedron ─────────────────────────────────────────────────────
    private fun makeDodecahedron(): Shape {
        val phi = (1f + sqrt(5f)) / 2f
        val iPhi = 1f / phi
        val one  = 1f / sqrt(3f)   // normalise so all vertices sit on unit sphere
        val ip   = iPhi / sqrt(3f)
        val pp   = phi  / sqrt(3f)

        // 20 vertices
        val verts = listOf(
            // cube vertices (±1, ±1, ±1)
            Vec3(-one,-one,-one), Vec3(-one,-one, one),
            Vec3(-one, one,-one), Vec3(-one, one, one),
            Vec3( one,-one,-one), Vec3( one,-one, one),
            Vec3( one, one,-one), Vec3( one, one, one),
            // (0, ±1/φ, ±φ)
            Vec3( 0f, -ip, -pp), Vec3( 0f, -ip,  pp),
            Vec3( 0f,  ip, -pp), Vec3( 0f,  ip,  pp),
            // (±1/φ, ±φ, 0)
            Vec3(-ip, -pp, 0f),  Vec3(-ip,  pp, 0f),
            Vec3( ip, -pp, 0f),  Vec3( ip,  pp, 0f),
            // (±φ, 0, ±1/φ)
            Vec3(-pp, 0f, -ip),  Vec3(-pp, 0f,  ip),
            Vec3( pp, 0f, -ip),  Vec3( pp, 0f,  ip)
        )

        val edgeLen = dist3(verts[0], verts[8])   // known short edge of dodecahedron
        val edges   = mutableListOf<Pair<Int, Int>>()
        for (i in verts.indices) {
            for (j in i + 1 until verts.size) {
                if (abs(dist3(verts[i], verts[j]) - edgeLen) < 0.03f) {
                    edges += i to j
                }
            }
        }
        return Shape("Dodecahedron", verts, edges)
    }

    // ─── Hexagonal Prism ──────────────────────────────────────────────────
    private fun makeHexagonalPrism(): Shape {
        val r = 0.55f; val h = 0.4f
        val bottom = (0 until 6).map { i ->
            val a = i * PI.toFloat() / 3f
            Vec3(cos(a) * r, -h, sin(a) * r)
        }
        val top = (0 until 6).map { i ->
            val a = i * PI.toFloat() / 3f
            Vec3(cos(a) * r,  h, sin(a) * r)
        }
        val verts = bottom + top
        val edges = mutableListOf<Pair<Int, Int>>()
        for (i in 0 until 6) edges += i to (i + 1) % 6           // bottom ring
        for (i in 6 until 12) edges += i to 6 + (i - 6 + 1) % 6  // top ring
        for (i in 0 until 6) edges += i to i + 6                   // vertical struts
        return Shape("Hexagonal Prism", verts, edges)
    }

    // ─── Diamond (elongated bi-pyramid) ───────────────────────────────────
    private fun makeDiamond(): Shape {
        val r = 0.45f; val top = 0.75f; val bot = -0.55f; val mid = 0.05f
        val belt = (0 until 8).map { i ->
            val a = i * PI.toFloat() / 4f
            Vec3(cos(a) * r, mid, sin(a) * r)
        }
        val verts = listOf(Vec3(0f, top, 0f)) + belt + listOf(Vec3(0f, bot, 0f))
        val edges = mutableListOf<Pair<Int, Int>>()
        for (i in 1..8) {
            edges += 0 to i                         // top crown
            edges += i to 9                         // bottom keel
            edges += i to (i % 8 + 1)              // girdle
        }
        return Shape("Diamond", verts, edges)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════════

    private fun dist3(a: Vec3, b: Vec3): Float {
        val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun currentTimeString(): String {
        val cal = java.util.Calendar.getInstance()
        val h   = cal.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val m   = cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
        val s   = cal.get(java.util.Calendar.SECOND).toString().padStart(2, '0')
        return "$h:$m:$s"
    }

    private fun randomDelay(): Long =
        5_000L + (Math.random() * 25_000).toLong()   // 5 s – 30 s
}
