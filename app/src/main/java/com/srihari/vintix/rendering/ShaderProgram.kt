package com.srihari.vintix.rendering

import android.opengl.GLES20
import android.util.Log

/**
 * Modular shader program manager.
 * Compiles GLSL vertex/fragment shaders, links them into a program,
 * and provides uniform/attribute access.
 *
 * Designed for future shader chaining — each effect pass can use
 * its own [ShaderProgram] instance with different fragment shaders.
 */
class ShaderProgram {

    companion object {
        private const val TAG = "ShaderProgram"
    }

    /** The linked OpenGL program ID. Valid after [link] succeeds. */
    var programId: Int = 0
        private set

    /**
     * Compiles a shader from GLSL source.
     *
     * @param type [GLES20.GL_VERTEX_SHADER] or [GLES20.GL_FRAGMENT_SHADER]
     * @param source The GLSL source code string
     * @return The compiled shader ID, or 0 on failure
     */
    fun compile(type: Int, source: String): Int {
        val typeName = if (type == GLES20.GL_VERTEX_SHADER) "VERTEX" else "FRAGMENT"
        Log.d(TAG, "Compiling $typeName shader (source length=${source.length})")

        val shader = GLES20.glCreateShader(type)
        if (shader == 0) {
            val err = GLES20.glGetError()
            Log.e(TAG, "Failed to create $typeName shader (type=$type, glError=$err)")
            return 0
        }

        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val info = GLES20.glGetShaderInfoLog(shader)
            Log.e(TAG, "========================================")
            Log.e(TAG, "SHADER COMPILATION FAILED ($typeName)")
            Log.e(TAG, "Log: $info")
            Log.e(TAG, "========================================")
            GLES20.glDeleteShader(shader)
            return 0
        }

        Log.d(TAG, "$typeName shader compiled successfully (id=$shader)")
        return shader
    }

    /**
     * Links vertex and fragment shaders into a program.
     *
     * @param vertexShader Compiled vertex shader ID
     * @param fragmentShader Compiled fragment shader ID
     * @return true if successful
     */
    fun link(vertexShader: Int, fragmentShader: Int): Boolean {
        programId = GLES20.glCreateProgram()
        if (programId == 0) {
            Log.e(TAG, "Failed to create GL program")
            return false
        }

        GLES20.glAttachShader(programId, vertexShader)
        GLES20.glAttachShader(programId, fragmentShader)
        GLES20.glLinkProgram(programId)

        val status = IntArray(1)
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val info = GLES20.glGetProgramInfoLog(programId)
            Log.e(TAG, "========================================")
            Log.e(TAG, "PROGRAM LINKING FAILED")
            Log.e(TAG, "Log: $info")
            Log.e(TAG, "========================================")
            GLES20.glDeleteProgram(programId)
            programId = 0
            return false
        }

        Log.d(TAG, "Program linked successfully (id=$programId)")
        return true
    }

    /** Binds this program for rendering. */
    fun use() {
        GLES20.glUseProgram(programId)
    }

    /**
     * Returns the location of a uniform variable.
     * @param name The uniform name in the GLSL source
     */
    fun getUniformLocation(name: String): Int {
        return GLES20.glGetUniformLocation(programId, name)
    }

    /**
     * Returns the location of an attribute variable.
     * @param name The attribute name in the GLSL source
     */
    fun getAttribLocation(name: String): Int {
        return GLES20.glGetAttribLocation(programId, name)
    }

    /** Deletes the program and releases GPU resources. */
    fun release() {
        if (programId != 0) {
            GLES20.glDeleteProgram(programId)
            Log.d(TAG, "Program released (id=$programId)")
            programId = 0
        }
    }
}
