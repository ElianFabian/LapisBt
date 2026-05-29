package com.elianfabian.lapisbt.util

import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.channels.Channels
import java.nio.channels.Pipe

/**
 * Creates two bidirectional pipes cross-linked to represent two sides of a connection.
 * Data written to [sideA.outputStream] is readable from [sideB.inputStream], and vice versa.
 */
public class BidirectionalStreamPipe() {

    private val pipeA = Pipe.open()
    private val pipeB = Pipe.open()

    private val outA = Channels.newOutputStream(pipeA.sink())
    private val inB = Channels.newInputStream(pipeA.source())

    private val outB = Channels.newOutputStream(pipeB.sink())
    private val inA = Channels.newInputStream(pipeB.source())

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
