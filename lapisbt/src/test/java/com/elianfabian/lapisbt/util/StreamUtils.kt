package com.elianfabian.lapisbt.util

import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * Creates two bidirectional pipes cross-linked to represent two sides of a connection.
 * Data written to [sideA.outputStream] is readable from [sideB.inputStream], and vice versa.
 */
internal class BidirectionalStreamPipe(bufferSize: Int = 65536) {
    private val outA = PipedOutputStream()
    private val inB = PipedInputStream(outA, bufferSize)
    
    private val outB = PipedOutputStream()
    private val inA = PipedInputStream(outB, bufferSize)

    val sideA = ConnectionSide(inA, outA)
    val sideB = ConnectionSide(inB, outB)

    data class ConnectionSide(
        val inputStream: InputStream,
        val outputStream: OutputStream
    )

    fun close() {
        try {
            outA.close()
            inB.close()
            outB.close()
            inA.close()
        } catch (e: Exception) {
            // Ignore close errors
        }
    }
}
