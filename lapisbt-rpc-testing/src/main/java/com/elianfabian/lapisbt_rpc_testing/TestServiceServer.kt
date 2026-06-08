package com.elianfabian.lapisbt_rpc_testing

import com.elianfabian.lapisbt_rpc.getLapisRequestInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class TestServiceServer : TestService {
	override suspend fun greet(name: String): String {
		return "Hello, $name!"
	}

	override suspend fun add(a: Int, b: Int): Int {
		return a + b
	}

	override fun counter(): Flow<Int> = flow {
		repeat(10) {
			emit(it)
			delay(100)
		}
	}

	override suspend fun processFlow(data: Flow<Int>): Int {
		var sum = 0
		data.collect { sum += it }
		return sum
	}

	override suspend fun getRequestInfo(): String {
		val info = getLapisRequestInfo()
		return "Address: ${info.deviceAddress.value}, Metadata: ${info.request.metadata}"
	}
}

class SecondaryServiceServer : SecondaryService {
	override suspend fun ping(): String {
		return "pong"
	}
}
