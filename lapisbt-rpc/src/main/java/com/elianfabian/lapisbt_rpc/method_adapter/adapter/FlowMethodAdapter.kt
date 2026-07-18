package com.elianfabian.lapisbt_rpc.method_adapter.adapter

import com.elianfabian.lapisbt.logger.LapisLogger
import com.elianfabian.lapisbt.logger.LapisLogger.Companion.debug
import com.elianfabian.lapisbt.logger.LapisLogger.Companion.verbose
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt_rpc.LapisRequestInfoContext
import com.elianfabian.lapisbt_rpc.exception.LocalException
import com.elianfabian.lapisbt_rpc.exception.RemoteCancellationException
import com.elianfabian.lapisbt_rpc.getLapisRequestInfo
import com.elianfabian.lapisbt_rpc.method_adapter.BluetoothDeviceRpc
import com.elianfabian.lapisbt_rpc.method_adapter.LapisMethodAdapter
import com.elianfabian.lapisbt_rpc.method_adapter.LapisServerService
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
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass

// TODO: it would be nice to be able to reuse this logic for flow parameters
internal class FlowMethodAdapter(
	private val deviceAddress: BluetoothDevice.Address,
	private val bluetoothDeviceRpc: BluetoothDeviceRpc,
	private val logger: LapisLogger,
) : LapisMethodAdapter {

	private val _scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	private val _pendingChannelsByRequestId = ConcurrentHashMap<Int, SendChannel<Any?>>()
	private val _activeServerJobs = ConcurrentHashMap<Int, Job>()

	private val _flowEnd = Any()


	override fun dispose() {
		val message = "Adapter for device '$deviceAddress' is being disposed"

		logger.verbose(TAG) {
			message
		}

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
		requestId: Int,
		serviceInterface: Class<*>,
		method: Method,
		args: Array<out Any?>?,
	): Any {

		// We convert it to a SharedFlow so that we don't waste resources creating multiple requests
		// every time there's a new subscriber
		// We also set "started" to "SharingStarted.WhileSubscribed(stopTimeoutMillis = 0)" since we want
		// to immediately stop receiving values when the collection is canceled
		// The user of this library can call the "shareIn(...)" operator again and set the parameters
		// according to their needs
		return callbackFlow {
			_pendingChannelsByRequestId[requestId] = this@callbackFlow

			bluetoothDeviceRpc.sendRequest(
				requestId = requestId,
				serviceInterface = serviceInterface,
				method = method,
				args = args,
			)

			awaitClose {
				logger.debug(TAG) {
					"FlowMethodAdapter: Flow collection for request $requestId (${method.name}) is closing (isActive=$isActive)"
				}
				_pendingChannelsByRequestId.remove(requestId)
				_scope.launch {
					bluetoothDeviceRpc.cancel(requestId = requestId)
				}
			}
		}
			.buffer(capacity = Int.MAX_VALUE)
			.shareIn(
				scope = _scope,
				started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 0),
				replay = 0,
			).transformWhile { value ->
				if (value === _flowEnd) {
					false
				}
				else {
					emit(value)
					true
				}
			}
	}

	override suspend fun onReceiveRequest(request: LapisRequest, server: LapisServerService) {

		logger.debug(TAG) {
			"FlowMethodAdapter: Handling incoming Flow request for ${getLapisRequestInfo()}"
		}

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
							bluetoothDeviceRpc.cancel(requestId = request.requestId)
						}
						is LocalException -> {
							throw throwable.cause!!
						}
						else -> {
							bluetoothDeviceRpc.sendErrorMessage(
								requestId = request.requestId,
								message = throwable.message.toString(),
							)
						}
					}
				}
				.collect { value ->
					bluetoothDeviceRpc.sendResult(
						requestId = request.requestId,
						result = value,
					)

					logger.debug(TAG) {
						"FlowMethodAdapter: Emitting value for request ${request.requestId} (isActive=${coroutineContext.isActive})"
					}
				}
			bluetoothDeviceRpc.sendEnd(requestId = request.requestId)
		}

		_activeServerJobs[request.requestId] = job

		job.join()
	}

	override fun onEnd(requestId: Int) {
		logger.debug(TAG) {
			"FlowMethodAdapter: Flow for request $requestId has ended"
		}

		val channel = _pendingChannelsByRequestId.remove(requestId)
		channel?.trySend(_flowEnd)
		channel?.close()
	}

	override fun onCancel(requestId: Int) {
		logger.debug(TAG) {
			"FlowMethodAdapter: Flow for request $requestId was cancelled"
		}

		// We force it to be a local exception so that we don't send a cancellation message to the client
		// Cancellation should happen from the client to the server and not the other way around
		_activeServerJobs.remove(requestId)?.cancel(RemoteCancellationException("Remote cancellation from device with address '$deviceAddress'"))
	}

	override fun onResult(requestId: Int, result: Any?) {
		logger.debug(TAG) {
			"FlowMethodAdapter: Received result for request $requestId: $result"
		}

		_pendingChannelsByRequestId[requestId]?.trySend(result)
	}

	override fun onErrorMessage(requestId: Int, throwable: Throwable) {
		logger.debug(TAG) {
			"FlowMethodAdapter: Error in Flow for request $requestId: ${throwable.message}"
		}
		_pendingChannelsByRequestId.remove(requestId)?.close(throwable)
	}

	override fun onDeviceDisconnected(deviceAddress: BluetoothDevice.Address) {
		_pendingChannelsByRequestId.forEach { (_, channel) ->
			channel.close(CancellationException("Device '$deviceAddress' disconnected"))
		}
		_pendingChannelsByRequestId.clear()

		_activeServerJobs.forEach { (_, job) ->
			job.cancel(CancellationException("Device '$deviceAddress' disconnected"))
		}
		_activeServerJobs.clear()
	}

	override suspend fun onAllRequestsFailed(throwable: Throwable) {
		_pendingChannelsByRequestId.forEach { (_, channel) ->
			channel.close(throwable)
		}
		_pendingChannelsByRequestId.clear()

		_activeServerJobs.forEach { (_, job) ->
			job.cancel(CancellationException("Internal error", throwable))
		}
		_activeServerJobs.clear()
	}


	companion object {
		private val TAG = FlowMethodAdapter::class.simpleName!!
	}
}
