package com.elianfabian.lapisbt_rpc_benchmarks

import kotlin.system.measureNanoTime

object Benchmark {

    data class Result(
        val name: String,
        val iterations: Int,
        val totalTimeNs: Long,
    ) {
        val averageTimeNs: Double = totalTimeNs.toDouble() / iterations
        val averageTimeMs: Double = averageTimeNs / 1_000_000.0
        val operationsPerSecond: Double = 1_000_000_000.0 / averageTimeNs

        override fun toString(): String {
            return String.format(
                "Benchmark: %-30s | Iterations: %d | Avg: %.4f ms | Ops/sec: %.2f",
                name, iterations, averageTimeMs, operationsPerSecond
            )
        }
    }

    suspend fun run(
        name: String,
        warmup: Int = 5,
        iterations: Int = 10,
        block: suspend () -> Unit
    ): Result {
        println("Starting benchmark: $name")
        
        println("Warming up for $warmup times")
        repeat(warmup) {
            block()
        }

        println("Starting execute $iterations iterations")
        val totalTimeNs = measureNanoTime {
            repeat(iterations) {
                block()
            }
        }

        val result = Result(name, iterations, totalTimeNs)
        println(result)
        return result
    }
}
