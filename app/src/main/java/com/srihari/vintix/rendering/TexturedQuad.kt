package com.srihari.vintix.rendering

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * A fullscreen textured quad in normalized device coordinates (-1 to 1).
 * This is the primary geometry primitive for the rendering pipeline —
 * camera frames and post-processing effects are rendered onto this surface.
 *
 * Uses [GL_TRIANGLE_STRIP] with 4 vertices for efficient fullscreen coverage.
 * Texture coordinates are set up for Android's coordinate system.
 */
class TexturedQuad {

    companion object {
        /** Floats per vertex: x, y (position) + u, v (texcoord) */
        private const val FLOATS_PER_VERTEX = 4
        private const val FLOAT_SIZE_BYTES = 4
        private const val STRIDE = FLOATS_PER_VERTEX * FLOAT_SIZE_BYTES

        /**
         * Vertex data: position (x,y) + texcoord (u,v)
         * Standard OpenGL coordinates: (0,0) at bottom-left.
         */
        private val VERTEX_DATA = floatArrayOf(
            // x,    y,    u,   v
            -1f, -1f,  0f, 0f,   // bottom-left
             1f, -1f,  1f, 0f,   // bottom-right
            -1f,  1f,  0f, 1f,   // top-left
             1f,  1f,  1f, 1f    // top-right
        )

        private const val VERTEX_COUNT = 4
    }

    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(VERTEX_DATA.size * FLOAT_SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(VERTEX_DATA)
            position(0)
        }

    /**
     * Draws the quad using the given shader program.
     * Expects the shader to have `aPosition` (vec4) and `aTexCoord` (vec2) attributes.
     *
     * @param shaderProgram The compiled and linked shader program to draw with
     */
    fun draw(shaderProgram: ShaderProgram) {
        val positionHandle = shaderProgram.getAttribLocation("aPosition")
        val texCoordHandle = shaderProgram.getAttribLocation("aTexCoord")

        // Bind position attribute (first 2 floats per vertex)
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(
            positionHandle,
            2,                  // x, y
            GLES20.GL_FLOAT,
            false,
            STRIDE,
            vertexBuffer
        )

        // Bind texcoord attribute (next 2 floats per vertex)
        vertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(
            texCoordHandle,
            2,                  // u, v
            GLES20.GL_FLOAT,
            false,
            STRIDE,
            vertexBuffer
        )

        // Draw the quad as a triangle strip
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)

        // Clean up attribute arrays
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    /** Releases vertex buffer resources. */
    fun release() {
        vertexBuffer.clear()
    }
}
