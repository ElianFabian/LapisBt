package com.elianfabian.lapisbt_rpc.method_adapter.adapter

import com.elianfabian.lapisbt_rpc.LapisRequestInfoContext
import com.elianfabian.lapisbt_rpc.exception.DeviceNotConnectedException
import com.elianfabian.lapisbt_rpc.exception.LocalException
import com.elianfabian.lapisbt_rpc.exception.RemoteCancellationException
import com.elianfabian.lapisbt_rpc.getLapisRequestInfo
import com.elianfabian.lapisbt_rpc.method_adapter.LapisMethodAdapter
import com.elianfabian.lapisbt_rpc.method_adapter.LapisServerService
import com.elianfabian.lapisbt_rpc.method_adapter.MethodCommunicator
import com.elianfabian.lapisbt_rpc.model.LapisRequest
import com.elianfabian.lapisbt_rpc.util.getFlowReturnType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

internal class FlowMethodAdapter(
	private val deviceAddress: String,
	private val methodCommunicator: MethodCommunicator,
) : LapisMethodAdapter {

	private val _scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	private val _pendingChannelsByRequestId = ConcurrentHashMap<UUID, SendChannel<Any?>>()
	private val _activeServerJobs = ConcurrentHashMap<UUID, Job>()


	override fun onRegister() {
		// no-op
	}

	override fun onUnregister() {
		// TODO: think of cancellation messages, since dispose implies a forced disconnection
		//  maybe all messages should just be the refers to the disconnection itself
		val message = "BluetoothDeviceRpc for '$deviceAddress' is being disposed"

		_scope.cancel(CancellationException(message))

		_activeServerJobs.forEach { (_, job) ->
			job.cancel(CancellationException(message))
		}
		_activeServerJobs.clear()
	}

	override fun getOutputType(method: Method): KClass<*> {
		return method.getFlowReturnType().kotlin
	}

	override fun shouldIntercept(method: Method): Boolean = method.returnType.kotlin == Flow::class

	override fun functionCall(
		serviceInterface: Class<*>,
		method: Method,
		args: Array<out Any?>?,
		onGenerateRequestId: (requestId: UUID) -> Unit,
	): Any {

		// We convert it to a SharedFlow so that we don't waste resources creating multiple requests
		// every time there's a new subscriber
		// We also set "started" to "SharingStarted.WhileSubscribed(stopTimeoutMillis = 0)" since we want
		// to immediately stop receiving values when the collection is canceled
		// The user of this library can call the "shareIn(...)" operator again and set the parameters
		// according to their needs
		return callbackFlow {
			val requestId = UUID.randomUUID()

			onGenerateRequestId(requestId)

			_pendingChannelsByRequestId[requestId] = this@callbackFlow

			methodCommunicator.sendRequest(
				requestId = requestId,
				serviceInterface = serviceInterface,
				method = method,
				args = args,
			)

			awaitClose {
				println("$$$ Flow($isActive) del requestId $requestId recolectado/cancelado localmente")
				_pendingChannelsByRequestId.remove(requestId)
				_scope.launch {
					println("$$$$ cancel callbackFlow")
					methodCommunicator.cancel(requestId = requestId)
				}
			}
		}.shareIn(
			scope = _scope,
			started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 0),
			replay = 0,
		)
	}

	override suspend fun onReceiveRequest(request: LapisRequest, server: LapisServerService) {

		println("$$$ onReceiveRequest: ${getLapisRequestInfo()}")

		val job = _scope.launch(LapisRequestInfoContext(getLapisRequestInfo())) {
			val flow = server.invokeMethod() as Flow<Any?>

			flow
				.onCompletion { throwable ->
					when (throwable) {
						null -> {
							_activeServerJobs.remove(request.requestId)
						}
						is RemoteCancellationException -> {
							// no-op
						}
						is CancellationException -> {
							methodCommunicator.cancel(requestId = request.requestId)
						}
						is LocalException -> {
							throw throwable.cause!!
						}
						else -> {
							methodCommunicator.sendErrorMessage(
								requestId = request.requestId,
								message = throwable.message.toString(),
							)
						}
					}
				}
				.collect { value ->
					methodCommunicator.sendResult(
						requestId = request.requestId,
						result = value,
					)

					println("$$$ onReceiveRequest.flow(${coroutineContext.isActive}): request = $request, emission = $value")
				}
			methodCommunicator.sendEnd(requestId = request.requestId)
		}

		_activeServerJobs[request.requestId] = job

		job.join()
	}

	override fun onEnd(requestId: UUID) {
		println("$$$ onEnd: $requestId")
		_pendingChannelsByRequestId.remove(requestId)?.close()
	}

	override fun onCancel(requestId: UUID) {
		println("$$$ onCancel: $requestId")

		// We force it to be a local exception so that we don't send a cancellation message to the client
		// Cancellation should happen from the client to the server and not the other way around
		_activeServerJobs.remove(requestId)?.cancel(RemoteCancellationException("Remote cancellation from device with address '$deviceAddress'"))

		_pendingChannelsByRequestId.remove(requestId)?.close(RemoteCancellationException("Remote cancellation from device with address '$deviceAddress'"))
	}

	override fun onResult(requestId: UUID, result: Any?) {
		println("$$$ onResult($requestId) = $result")
		_scope.launch {
			_pendingChannelsByRequestId[requestId]?.send(result)
		}
	}

	override fun onErrorMessage(requestId: UUID, throwable: Throwable) {
		println("$$$ onErrorMessage($requestId): $throwable")
		_pendingChannelsByRequestId.remove(requestId)?.close(throwable)
	}

	override fun onDeviceDisconnected(deviceAddress: String) {
		_scope.cancel(CancellationException("Device '$deviceAddress' disconnected"))
		_pendingChannelsByRequestId.forEach { (_, channel) ->
			channel.close(DeviceNotConnectedException(deviceAddress))
		}
		_pendingChannelsByRequestId.clear()

		_activeServerJobs.forEach { (_, job) ->
			job.cancel(CancellationException("Device '$deviceAddress' disconnected"))
		}
		_activeServerJobs.clear()
	}
}
