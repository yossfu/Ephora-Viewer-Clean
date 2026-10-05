package android.opengl

import android.view.ViewGroup

/** Stub of the GLES20 static surface: only what the renderer calls. */
object GLES20 {
    const val GL_DEPTH_BUFFER_BIT = 0x00000100
    const val GL_STENCIL_BUFFER_BIT = 0x00000400
    const val GL_COLOR_BUFFER_BIT = 0x00004000
    const val GL_FALSE = 0
    const val GL_TRUE = 1
    const val GL_POINTS = 0x0000
    const val GL_LINES = 0x0001
    const val GL_TRIANGLES = 0x0004
    const val GL_DEPTH_TEST = 0x0B71
    const val GL_CULL_FACE = 0x0B44
    const val GL_BLEND = 0x0BE2
    const val GL_BACK = 0x0405
    const val GL_FRONT = 0x0404
    const val GL_CW = 0x0900
    const val GL_CCW = 0x0901
    const val GL_LEQUAL = 0x0203
    const val GL_LESS = 0x0201
    const val GL_EQUAL = 0x0202
    const val GL_SRC_ALPHA = 0x0302
    const val GL_ONE_MINUS_SRC_ALPHA = 0x0303
    const val GL_ONE = 1
    const val GL_FLOAT = 0x1406
    const val GL_UNSIGNED_BYTE = 0x1401
    const val GL_UNSIGNED_SHORT = 0x1403
    const val GL_UNSIGNED_INT = 0x1405
    const val GL_ARRAY_BUFFER = 0x8892
    const val GL_ELEMENT_ARRAY_BUFFER = 0x8893
    const val GL_STATIC_DRAW = 0x88E4
    const val GL_DYNAMIC_DRAW = 0x88E8
    const val GL_TEXTURE_2D = 0x0DE1
    const val GL_TEXTURE0 = 0x84C0
    const val GL_TEXTURE_MIN_FILTER = 0x2801
    const val GL_TEXTURE_MAG_FILTER = 0x2800
    const val GL_TEXTURE_WRAP_S = 0x2802
    const val GL_TEXTURE_WRAP_T = 0x2803
    const val GL_NEAREST = 0x2600
    const val GL_LINEAR = 0x2601
    const val GL_CLAMP_TO_EDGE = 0x812F
    const val GL_REPEAT = 0x2901
    const val GL_VERTEX_SHADER = 0x8B31
    const val GL_FRAGMENT_SHADER = 0x8B30
    const val GL_COMPILE_STATUS = 0x8B81
    const val GL_LINK_STATUS = 0x8B82
    const val GL_VERSION = 0x1F02
    const val GL_RENDERER = 0x1F01
    const val GL_NO_ERROR = 0
    const val GL_INVALID_ENUM = 0x0500
    const val GL_INVALID_VALUE = 0x0501
    const val GL_INVALID_OPERATION = 0x0502
    const val GL_MAX_TEXTURE_SIZE = 0x0D33

    fun glCreateShader(type: Int): Int = 0
    fun glShaderSource(shader: Int, source: String) {}
    fun glCompileShader(shader: Int) {}
    fun glGetShaderiv(shader: Int, pname: Int, params: IntArray, offset: Int) {}
    fun glGetShaderInfoLog(shader: Int): String = ""
    fun glDeleteShader(shader: Int) {}
    fun glCreateProgram(): Int = 0
    fun glAttachShader(program: Int, shader: Int) {}
    fun glLinkProgram(program: Int) {}
    fun glGetProgramiv(program: Int, pname: Int, params: IntArray, offset: Int) {}
    fun glGetProgramInfoLog(program: Int): String = ""
    fun glDeleteProgram(program: Int) {}
    fun glUseProgram(program: Int) {}
    fun glGetUniformLocation(program: Int, name: String): Int = 0
    fun glGetAttribLocation(program: Int, name: String): Int = 0
    fun glBindAttribLocation(program: Int, index: Int, name: String) {}
    fun glUniform1f(location: Int, x: Float) {}
    fun glUniform2f(location: Int, x: Float, y: Float) {}
    fun glUniform3f(location: Int, x: Float, y: Float, z: Float) {}
    fun glUniform4f(location: Int, x: Float, y: Float, z: Float, w: Float) {}
    fun glUniform1i(location: Int, x: Int) {}
    fun glUniformMatrix4fv(location: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int) {}
    fun glEnable(cap: Int) {}
    fun glDisable(cap: Int) {}
    fun glDepthFunc(func: Int) {}
    fun glDepthMask(flag: Boolean) {}
    fun glCullFace(mode: Int) {}
    fun glFrontFace(mode: Int) {}
    fun glBlendFunc(sfaktor: Int, dfactor: Int) {}
    fun glClear(mask: Int) {}
    fun glClearColor(red: Float, green: Float, blue: Float, alpha: Float) {}
    fun glViewport(x: Int, y: Int, width: Int, height: Int) {}
    fun glGenBuffers(count: Int, buffers: IntArray, offset: Int) {}
    fun glBindBuffer(target: Int, buffer: Int) {}
    fun glBufferData(target: Int, size: Int, data: java.nio.Buffer?, usage: Int) {}
    fun glBufferData(target: Int, data: java.nio.Buffer?, usage: Int) {}
    fun glDeleteBuffers(count: Int, buffers: IntArray, offset: Int) {}
    fun glEnableVertexAttribArray(index: Int) {}
    fun glDisableVertexAttribArray(index: Int) {}
    fun glVertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int) {}
    fun glDrawElements(mode: Int, count: Int, type: Int, offset: Int) {}
    fun glDrawArrays(mode: Int, first: Int, count: Int) {}
    fun glGenTextures(count: Int, textures: IntArray, offset: Int) {}
    fun glBindTexture(target: Int, texture: Int) {}
    fun glDeleteTextures(count: Int, textures: IntArray, offset: Int) {}
    fun glTexParameteri(target: Int, pname: Int, param: Int) {}
    fun glActiveTexture(texture: Int) {}
    fun glGetError(): Int = 0
    fun glGetString(name: Int): String = ""
}

object GLUtils {
    fun texImage2D(target: Int, level: Int, bitmap: android.graphics.Bitmap, border: Int) {}
}

open class GLSurfaceView : ViewGroup {
    constructor(context: android.content.Context?) : super(context)
    constructor(context: android.content.Context?, attrs: android.util.AttributeSet?) : super(context, attrs)

    interface Renderer {
        fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?)
        fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int)
        fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?)
    }

    fun setRenderer(renderer: Renderer) {}
    fun setEGLContextClientVersion(version: Int) {}
    fun setEGLConfigChooser(redSize: Int, greenSize: Int, blueSize: Int, alphaSize: Int, depthSize: Int, stencilSize: Int) {}
    fun setPreserveEGLContextOnPause(preserve: Boolean) {}
    fun setRenderMode(mode: Int) {}
    fun queueEvent(runnable: Runnable) {}
    open fun onPause() {}
    open fun onResume() {}

    companion object {
        const val RENDERMODE_WHEN_DIRTY = 0
        const val RENDERMODE_CONTINUOUSLY = 1
    }
}
