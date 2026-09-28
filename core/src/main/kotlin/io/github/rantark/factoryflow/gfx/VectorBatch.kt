package io.github.rantark.factoryflow.gfx

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.VertexAttribute
import com.badlogic.gdx.graphics.VertexAttributes
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.Disposable

/**
 * Immediate-mode triangle batch. All shapes and glyphs are collected into one vertex
 * array and drawn with a single shader/texture, flushing only when the buffer fills,
 * the blend mode changes, or a cached mesh (terrain chunk) needs drawing.
 */
class VectorBatch(maxVertices: Int = 30000) : Shapes(maxVertices * VSIZE), Disposable {
    private val mesh = Mesh(false, maxVertices, 0, attributes())
    val shader: ShaderProgram = createShader()
    lateinit var texture: Texture
    val projection = Matrix4()
    private var drawing = false
    private var additive = false
    /** Number of GPU draw calls issued this frame (for the debug overlay). */
    var drawCalls = 0

    override fun ensure(floats: Int) {
        if (idx + floats > verts.size) flush()
    }

    fun begin(proj: Matrix4, pxPerUnit: Float) {
        projection.set(proj)
        pixelScale = pxPerUnit
        Gdx.gl.glEnable(GL20.GL_BLEND)
        setAdditive(false, force = true)
        shader.bind()
        shader.setUniformMatrix("u_projTrans", projection)
        shader.setUniformi("u_texture", 0)
        texture.bind(0)
        drawing = true
    }

    fun setAdditive(on: Boolean, force: Boolean = false) {
        if (on == additive && !force) return
        if (drawing) flush()
        additive = on
        if (on) Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE)
        else Gdx.gl.glBlendFuncSeparate(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA, GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA)
    }

    fun flush() {
        if (idx == 0) return
        mesh.setVertices(verts, 0, idx)
        mesh.render(shader, GL20.GL_TRIANGLES, 0, idx / VSIZE)
        drawCalls++
        idx = 0
    }

    /** Draw a pre-baked static mesh (same vertex format) with the current state. */
    fun drawMesh(m: Mesh, vertexCount: Int) {
        flush()
        m.render(shader, GL20.GL_TRIANGLES, 0, vertexCount)
        drawCalls++
    }

    /** Temporarily draw with another texture (e.g. the minimap). */
    fun withTexture(t: Texture, block: () -> Unit) {
        flush()
        t.bind(0)
        block()
        flush()
        texture.bind(0)
    }

    fun end() {
        flush()
        drawing = false
    }

    override fun dispose() {
        mesh.dispose()
        shader.dispose()
    }

    companion object {
        const val VSIZE = 5

        fun attributes() = VertexAttributes(
            VertexAttribute(VertexAttributes.Usage.Position, 2, ShaderProgram.POSITION_ATTRIBUTE),
            VertexAttribute.ColorPacked(),
            VertexAttribute.TexCoords(0),
        )

        private val VERT = """
            attribute vec4 a_position;
            attribute vec4 a_color;
            attribute vec2 a_texCoord0;
            uniform mat4 u_projTrans;
            varying vec4 v_color;
            varying vec2 v_uv;
            void main() {
                v_color = a_color;
                v_color.a = v_color.a * (255.0 / 254.0);
                v_uv = a_texCoord0;
                gl_Position = u_projTrans * a_position;
            }
        """.trimIndent()

        private val FRAG = """
            #ifdef GL_ES
            precision mediump float;
            #endif
            varying vec4 v_color;
            varying vec2 v_uv;
            uniform sampler2D u_texture;
            void main() {
                gl_FragColor = v_color * texture2D(u_texture, v_uv);
            }
        """.trimIndent()

        fun createShader(): ShaderProgram {
            ShaderProgram.pedantic = false
            val s = ShaderProgram(VERT, FRAG)
            require(s.isCompiled) { "Shader failed: ${s.log}" }
            return s
        }
    }
}
