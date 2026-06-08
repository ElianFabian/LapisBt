package com.elianfabian.lapisbt_rpc.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

// This should be used for packet processing so that all packets from the same request
// are processed in the same coroutine to guarantee that they're processed in the right order
// but this doesn't seem to work, the make some tests to time out,
internal class KeyedCoroutineExecutor(private val scope: CoroutineScope) {

    private class WorkItem(
        val isTerminal: Boolean,
        val block: suspend () -> Unit
    )

    private val workers = ConcurrentHashMap<Any, Channel<WorkItem>>()

    /**
     * Enqueues a suspension block to be executed sequentially in a single coroutine
     * dedicated to the provided [id].
     */
    fun executeById(id: Any, isTerminal: Boolean = false, block: suspend () -> Unit) {
        val channel = workers.getOrPut(id) {
            val newChannel = Channel<WorkItem>(Channel.UNLIMITED)

            // Spin up the single dedicated coroutine for this specific ID
            scope.launch {
                try {
                    for (item in newChannel) {
                        runCatching { item.block() }

                        if (item.isTerminal) {
                            workers.remove(id, newChannel)
                            newChannel.close()
                            break
                        }
                    }
                } finally {
                    workers.remove(id, newChannel)
                }
            }
            newChannel
        }

        channel.trySend(WorkItem(isTerminal, block))
    }
}
