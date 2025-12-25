package com.elianfabian.lapisbt

import android.util.Log
import com.elianfabian.lapisbt.LapisBtIRpcImpl.Companion.BLUETOOTH_PACKET_LENGTH
import com.elianfabian.lapisbt.LapisBtIRpcImpl.Companion.Tag
import com.elianfabian.lapisbt.annotation.LapisBluetoothApi
import com.elianfabian.lapisbt.annotation.LapisBluetoothMethodCall
import com.elianfabian.lapisbt.annotation.LapisBluetoothParam
import com.elianfabian.lapisbt.model.BluetoothPacket
import com.elianfabian.lapisbt.model.CompleteBluetoothPacket
import com.elianfabian.lapisbt.model.LapisBluetoothRequest
import com.elianfabian.lapisbt.util.asEnumeration
import com.elianfabian.lapisbt.util.readNBytesCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.UUID
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class LapisBtIRpcImpl(
	private val lapisBt: LapisBt,
) : LapisBtRpc {

	private val _bluetoothClientApiByAddress = mutableMapOf<String, Any>()


	@Suppress("UNCHECKED_CAST")
	override fun <T> getOrCreateBluetoothApiClient(deviceAddress: String, apiInterface: Class<T>): T {
		val apiClient = _bluetoothClientApiByAddress[deviceAddress]
		if (apiClient != null) {
			return apiClient as T
		}

		val newApiClient = Proxy.newProxyInstance(
			apiInterface.classLoader,
			arrayOf(apiInterface),
			BluetoothApiClientInvocationHandler(
				deviceAddress = deviceAddress,
				lapisBt = lapisBt,
				apiInterface = apiInterface,
			),
		) as T

		_bluetoothClientApiByAddress[deviceAddress] = newApiClient as Any

		return newApiClient
	}

	override fun <T : Any> registerBluetoothApiServer(server: T) {
		TODO("Not yet implemented")
	}

	override fun unregisterBluetoothApiClient(deviceAddress: String) {
		TODO("Not yet implemented")
	}

	override fun unregisterBluetoothApiServer(server: Any) {
		TODO("Not yet implemented")
	}


	companion object {
		const val BLUETOOTH_PACKET_LENGTH = 256
		val Tag = LapisBtIRpcImpl::class.simpleName.orEmpty()
	}
}

// Given that we may support multiple APIs in the future, we have to send the request from the InvocationHandler,
// but read the requests in a common place
// so that we can route them to the correct API server implementation
private class BluetoothApiClientInvocationHandler(
	private val bluetoothDeviceRpc: BluetoothDeviceRpc,
	private val apiInterface: Class<*>,
) : InvocationHandler {



	@Suppress("UNCHECKED_CAST")
	override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
		return bluetoothDeviceRpc.functionCall(
			proxy = proxy,
			apiInterface = apiInterface,
			method = method,
			args = args,
		)
	}
}
