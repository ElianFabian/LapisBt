package com.elianfabian.lapisbt.util

import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * Creates two bidirectional pipes cross-linked to represent two sides of a connection.
 * Data written to [sideA.outputStream] is readable from [sideB.inputStream], and vice versa.
 */
public class BidirectionalStreamPipe(bufferSize: Int = 65536) {
    private val outA = PipedOutputStream()
    private val inB = PipedInputStream(outA, bufferSize)
    
    private val outB = PipedOutputStream()
    private val inA = PipedInputStream(outB, bufferSize)

    public val sideA: ConnectionSide = ConnectionSide(inA, outA)
    public val sideB: ConnectionSide = ConnectionSide(inB, outB)

    public data class ConnectionSide(
        public val inputStream: InputStream,
        public val outputStream: OutputStream
    )

    public fun close() {
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
