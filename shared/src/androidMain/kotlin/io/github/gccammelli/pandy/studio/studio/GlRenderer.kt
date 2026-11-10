// SPDX-License-Identifier: MIT
// Copyright (c) Giancarlo Cammelli
package io.github.gccammelli.pandy.studio.studio

import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import io.github.gccammelli.pandy.studio.gcode.Feature
import io.github.gccammelli.pandy.studio.gcode.GcodeModel
import io.github.gccammelli.pandy.studio.model.Mat4
import io.github.gccammelli.pandy.studio.model.Mesh
import io.github.gccammelli.pandy.studio.model.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/** What the GL thread draws: a copy taken on the UI thread whenever the scene changes. */
class Frame(
    val bed: Bed,
    val objects: List<Pair<Mesh, Mat4>>,
    val colors: List<FloatArray>,
    val selected: Int,
    val preview: GcodeModel?,
    val layerLow: Int,
    val layerHigh: Int,
    val dark: Boolean,
    val outside: Set<Int>,
)

/** Orbit camera around a target point on the bed; angles in degrees, Z up like the printer. */
class OrbitCamera {
    var yaw = -35f; var pitch = 38f; var distance = 420f
    var target = Vec3(128f, 128f, 0f)
    var width = 1; var height = 1

    fun eye(): Vec3 {
        val p = Math.toRadians(pitch.toDouble()); val y = Math.toRadians(yaw.toDouble())
        return target + Vec3((cos(p) * sin(y)).toFloat(), (-cos(p) * cos(y)).toFloat(), sin(p).toFloat()) * distance
    }

    fun viewProj(): Mat4 = Mat4.perspective(38f, width.toFloat() / height.coerceAtLeast(1), 2f, 6000f) * Mat4.lookAt(eye(), target, Vec3(0f, 0f, 1f))

    /** The ray through a screen point, as (origin, direction). */
    fun ray(sx: Float, sy: Float): Pair<Vec3, Vec3>? {
        val inv = viewProj().inverted() ?: return null
        val nx = 2f * sx / width - 1f; val ny = 1f - 2f * sy / height
        val near = inv.transformPoint(Vec3(nx, ny, -1f)); val far = inv.transformPoint(Vec3(nx, ny, 1f))
        return near to (far - near).normalized()
    }

    /** Where a screen point's ray meets the plane Z = [z]. */
    fun onPlane(sx: Float, sy: Float, z: Float = 0f): Vec3? {
        val (o, d) = ray(sx, sy) ?: return null
        if (kotlin.math.abs(d.z) < 1e-5f) return null
        val t = (z - o.z) / d.z
        return if (t > 0) o + d * t else null
    }

    fun frame(bed: Bed) {
        target = Vec3(bed.centerX, bed.centerY, 0f)
        distance = maxOf(bed.width, bed.depth) * 1.75f
    }
}

class GlRenderer(private val camera: OrbitCamera) : GLSurfaceView.Renderer {
    @Volatile var frame: Frame? = null
    private var meshProgram = 0
    private var lineProgram = 0
    private val meshCache = HashMap<Mesh, Pair<Int, Int>>() // mesh -> (vbo, vertex count)
    private var previewFor: GcodeModel? = null
    private var previewVbo = 0
    private var layerStarts = IntArray(0)
    private var bedVbo = 0; private var bedLines = 0; private var bedTris = 0; private var bedFor: Pair<Bed, Boolean>? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        meshProgram = program(MESH_VS, MESH_FS)
        lineProgram = program(LINE_VS, LINE_FS)
        meshCache.clear(); previewFor = null; bedFor = null
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        glViewport(0, 0, width, height); camera.width = width; camera.height = height
    }

    override fun onDrawFrame(gl: GL10?) {
        val f = frame
        val bg = if (f?.dark == true) floatArrayOf(.067f, .067f, .067f) else floatArrayOf(.957f, .957f, .957f)
        glClearColor(bg[0], bg[1], bg[2], 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        f ?: return
        val vp = camera.viewProj()
        drawBed(f, vp)
        if (f.preview != null) drawPreview(f, vp) else drawObjects(f, vp)
    }

    private fun drawObjects(f: Frame, vp: Mat4) {
        glUseProgram(meshProgram)
        val eye = camera.eye()
        f.objects.forEachIndexed { i, (mesh, model) ->
            val (vbo, count) = meshCache.getOrPut(mesh) { upload(mesh) }
            glUniformMatrix4fv(glGetUniformLocation(meshProgram, "uMvp"), 1, false, (vp * model).m, 0)
            glUniformMatrix4fv(glGetUniformLocation(meshProgram, "uModel"), 1, false, model.m, 0)
            val c = when { i in f.outside -> floatArrayOf(.9f, .28f, .3f, 1f); else -> f.colors.getOrElse(i) { f.colors.first() } }
            glUniform4fv(glGetUniformLocation(meshProgram, "uColor"), 1, c, 0)
            glUniform1f(glGetUniformLocation(meshProgram, "uSelected"), if (i == f.selected) 1f else 0f)
            glUniform3f(glGetUniformLocation(meshProgram, "uEye"), eye.x, eye.y, eye.z)
            glBindBuffer(GL_ARRAY_BUFFER, vbo)
            glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 24, 0)
            glEnableVertexAttribArray(1); glVertexAttribPointer(1, 3, GL_FLOAT, false, 24, 12)
            glDrawArrays(GL_TRIANGLES, 0, count)
        }
        // Drop meshes no longer on the plate.
        val live = f.objects.map { it.first }.toSet()
        meshCache.keys.filter { it !in live }.forEach { m -> glDeleteBuffers(1, intArrayOf(meshCache.remove(m)!!.first), 0) }
    }

    private fun upload(mesh: Mesh): Pair<Int, Int> {
        val (p, n) = mesh.toFlatShadedBuffers()
        val inter = FloatArray(p.size * 2)
        for (v in 0 until p.size / 3) { for (k in 0..2) { inter[v * 6 + k] = p[v * 3 + k]; inter[v * 6 + 3 + k] = n[v * 3 + k] } }
        return buffer(inter) to p.size / 3
    }

    private fun drawBed(f: Frame, vp: Mat4) {
        if (bedFor != f.bed to f.dark) {
            if (bedVbo != 0) glDeleteBuffers(1, intArrayOf(bedVbo), 0)
            val b = f.bed; val v = ArrayList<Float>()
            val plate = if (f.dark) floatArrayOf(.18f, .18f, .19f, 1f) else floatArrayOf(.86f, .86f, .87f, 1f)
            val grid = if (f.dark) floatArrayOf(.27f, .27f, .28f, 1f) else floatArrayOf(.76f, .76f, .78f, 1f)
            val edge = floatArrayOf(.68f, 0f, .43f, 1f)
            fun put(x: Float, y: Float, z: Float, c: FloatArray) { v += x; v += y; v += z; v += c[0]; v += c[1]; v += c[2]; v += c[3] }
            val x0 = b.originX; val y0 = b.originY; val x1 = x0 + b.width; val y1 = y0 + b.depth
            listOf(x0 to y0, x1 to y0, x1 to y1, x0 to y0, x1 to y1, x0 to y1).forEach { (x, y) -> put(x, y, -0.05f, plate) }
            bedTris = 6
            var n = 0
            var gx = x0 + 10f; while (gx < x1) { put(gx, y0, 0f, grid); put(gx, y1, 0f, grid); n += 2; gx += 10f }
            var gy = y0 + 10f; while (gy < y1) { put(x0, gy, 0f, grid); put(x1, gy, 0f, grid); n += 2; gy += 10f }
            listOf(x0 to y0, x1 to y0, x1 to y0, x1 to y1, x1 to y1, x0 to y1, x0 to y1, x0 to y0).forEach { (x, y) -> put(x, y, 0.02f, edge); n++ }
            bedLines = n
            bedVbo = buffer(v.toFloatArray()); bedFor = f.bed to f.dark
        }
        glUseProgram(lineProgram)
        glUniformMatrix4fv(glGetUniformLocation(lineProgram, "uMvp"), 1, false, vp.m, 0)
        glBindBuffer(GL_ARRAY_BUFFER, bedVbo)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 28, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 4, GL_FLOAT, false, 28, 12)
        glDrawArrays(GL_TRIANGLES, 0, bedTris)
        glLineWidth(1.5f)
        glDrawArrays(GL_LINES, bedTris, bedLines)
    }

    private fun drawPreview(f: Frame, vp: Mat4) {
        val model = f.preview!!
        if (previewFor !== model) {
            if (previewVbo != 0) glDeleteBuffers(1, intArrayOf(previewVbo), 0)
            val total = model.layers.sumOf { it.segments.size / 6 }
            val data = FloatArray(total * 2 * 7)
            layerStarts = IntArray(model.layers.size + 1)
            var k = 0
            model.layers.forEachIndexed { li, layer ->
                layerStarts[li] = k / 7
                for (s in 0 until layer.segments.size / 6) {
                    val c = FEATURE_COLORS[layer.features[s].toInt().coerceIn(0, FEATURE_COLORS.size - 1)]
                    for (e in 0..1) {
                        data[k++] = layer.segments[s * 6 + e * 3]; data[k++] = layer.segments[s * 6 + e * 3 + 1]; data[k++] = layer.segments[s * 6 + e * 3 + 2]
                        data[k++] = c[0]; data[k++] = c[1]; data[k++] = c[2]; data[k++] = 1f
                    }
                }
            }
            layerStarts[model.layers.size] = k / 7
            previewVbo = buffer(data); previewFor = model
        }
        val lo = (f.layerLow - 1).coerceIn(0, model.layers.size)
        val hi = f.layerHigh.coerceIn(lo, model.layers.size)
        glUseProgram(lineProgram)
        glUniformMatrix4fv(glGetUniformLocation(lineProgram, "uMvp"), 1, false, vp.m, 0)
        glBindBuffer(GL_ARRAY_BUFFER, previewVbo)
        glEnableVertexAttribArray(0); glVertexAttribPointer(0, 3, GL_FLOAT, false, 28, 0)
        glEnableVertexAttribArray(1); glVertexAttribPointer(1, 4, GL_FLOAT, false, 28, 12)
        glLineWidth(2f)
        glDrawArrays(GL_LINES, layerStarts[lo], layerStarts[hi] - layerStarts[lo])
    }

    private fun buffer(data: FloatArray): Int {
        val ids = IntArray(1); glGenBuffers(1, ids, 0)
        glBindBuffer(GL_ARRAY_BUFFER, ids[0])
        val fb: FloatBuffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
        fb.position(0)
        glBufferData(GL_ARRAY_BUFFER, data.size * 4, fb, GL_STATIC_DRAW)
        return ids[0]
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String) = glCreateShader(type).also {
            glShaderSource(it, src); glCompileShader(it)
            val ok = IntArray(1); glGetShaderiv(it, GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) android.util.Log.e("PandyStudio", "Shader failed: " + glGetShaderInfoLog(it))
        }
        return glCreateProgram().also { p ->
            glAttachShader(p, shader(GL_VERTEX_SHADER, vs)); glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs))
            glBindAttribLocation(p, 0, "aPos"); glBindAttribLocation(p, 1, "aAttr"); glLinkProgram(p)
        }
    }

    companion object {
        /** Bambu Studio's preview colours for each feature (GCodeViewer), in [Feature] order. */
        val FEATURE_COLORS: List<FloatArray> = listOf(
            "#FF7D38", "#FFE64D", "#0000FF", "#B03029", "#9654CC", "#F05C5C", "#4D5054", "#4D80BA", "#FFFFFF", "#00876E",
            "#00876E", "#00FF00", "#008000", "#00C8A0", "#B3E3AB", "#FF8C69", "#5E5E5E", "#38D4FF", "#A0A0A0",
        ).map { hex(it) }

        fun hex(s: String): FloatArray {
            val v = s.removePrefix("#").take(6).toLongOrNull(16) ?: 0xE0409C
            return floatArrayOf(((v shr 16) and 255) / 255f, ((v shr 8) and 255) / 255f, (v and 255) / 255f, 1f)
        }

        private const val MESH_VS = """#version 300 es
            layout(location=0) in vec3 aPos; layout(location=1) in vec3 aAttr;
            uniform mat4 uMvp; uniform mat4 uModel;
            out vec3 vNormal; out vec3 vWorld;
            void main() { gl_Position = uMvp * vec4(aPos, 1.0); vNormal = mat3(uModel) * aAttr; vWorld = (uModel * vec4(aPos, 1.0)).xyz; }"""
        private const val MESH_FS = """#version 300 es
            precision mediump float;
            in vec3 vNormal; in vec3 vWorld; uniform vec4 uColor; uniform float uSelected; uniform vec3 uEye;
            out vec4 color;
            void main() {
                vec3 n = normalize(vNormal); vec3 v = normalize(uEye - vWorld);
                float key = max(dot(n, normalize(vec3(-0.4, -0.6, 0.9))), 0.0);
                float fill = max(dot(n, normalize(vec3(0.7, 0.4, 0.3))), 0.0) * 0.35;
                float sky = 0.5 + 0.5 * n.z;
                vec3 base = uColor.rgb * (0.30 + 0.55 * key + fill + 0.15 * sky);
                float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0);
                base += uSelected * (vec3(1.0, 0.85, 0.95) * rim * 0.9 + vec3(0.06));
                color = vec4(base, uColor.a);
            }"""
        private const val LINE_VS = """#version 300 es
            layout(location=0) in vec3 aPos; layout(location=1) in vec4 aAttr; uniform mat4 uMvp; out vec4 vColor;
            void main() { gl_Position = uMvp * vec4(aPos, 1.0); vColor = aAttr; }"""
        private const val LINE_FS = """#version 300 es
            precision mediump float; in vec4 vColor; out vec4 color; void main() { color = vColor; }"""
    }
}
