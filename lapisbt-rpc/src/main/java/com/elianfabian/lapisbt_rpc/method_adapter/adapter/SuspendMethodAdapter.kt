package com.elianfabian.lapisbt_rpc.method_adapter.adapter

import com.elianfabian.lapisbt.logger.LapisLogger
import com.elianfabian.lapisbt.logger.LapisLogger.Companion.debug
import com.elianfabian.lapisbt.logger.LapisLogger.Companion.error
import com.elianfabian.lapisbt.logger.LapisLogger.Companion.verbose
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt_rpc.LapisRequestInfoContext
import com.elianfabian.lapisbt_rpc.exception.RemoteCancellationException
import com.elianfabian.lapisbt_rpc.getLapisRequestInfo
import com.elianfabian.lapisbt_rpc.method_adapter.BluetoothDeviceRpc
import com.elianfabian.lapisbt_rpc.method_adapter.LapisMethodAdapter
import com.elianfabian.lapisbt_rpc.method_adapter.LapisServerService
import com.elianfabian.lapisbt_rpc.model.LapisRequest
import com.elianfabian.lapisbt_rpc.util.getSuspendReturnType
import com.elianfabian.lapisbt_rpc.util.isSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine
import kotlin.reflect.KClass

internal class SuspendMethodAdapter(
	private val deviceAddress: BluetoothDevice.Address,
	private val bluetoothDeviceRpc: BluetoothDeviceRpc,
	private val logger: LapisLogger,
) : LapisMethodAdapter {

	private val _scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	private val _pendingContinuationsByRequestId = ConcurrentHashMap<Int, Continuation<Any?>>()
	private val _activeServerJobs = ConcurrentHashMap<Int, Job>()

	private val _nextId = AtomicInteger(0)


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

		_pendingContinuationsByRequestId.forEach { (_, continuation) ->
			if (continuation.context.isActive) {
				continuation.resumeWithException(CancellationException(message))
			}
		}
		_pendingContinuationsByRequestId.clear()
	}

	override fun getOutputType(method: Method): KClass<*> {
		return method.getSuspendReturnType().kotlin
	}

	override fun shouldIntercept(method: Method): Boolean = method.isSuspend()

	override fun functionCall(
		serviceInterface: Class<*>,
		method: Method,
		args: Array<out Any?>?,
		onGenerateRequestId: (requestId: Int) -> Unit,
	): Any {
		@Suppress("UNCHECKED_CAST")
		val continuation = args.orEmpty().last() as Continuation<Any?>
		return try {

			val rpcBlock = suspend {
				val requestId = generateId()

				onGenerateRequestId(requestId)

				suspendCancellableCoroutine { cancellableContinuation ->
					_pendingContinuationsByRequestId[requestId] = cancellableContinuation

					_scope.launch {
						logger.debug(TAG) {
							"start sendRequest: $requestId"
						}
						bluetoothDeviceRpc.sendRequest(
							requestId = requestId,
							serviceInterface = serviceInterface,
							method = method,
							args = args.orEmpty().dropLast(1).toTypedArray(),
						)
						logger.debug(TAG) {
							"end sendRequest: $requestId"
						}
					}

					cancellableContinuation.invokeOnCancellation { cause ->
						logger.debug(TAG) {
							"Request(${_pendingContinuationsByRequestId.size}) $requestId cancelled. Cause: $cause"
						}
						_pendingContinuationsByRequestId.remove(requestId)

						if (cause is CancellationException) {
							_scope.launch {
								try {
									bluetoothDeviceRpc.cancel(requestId = requestId)
								}
								catch (e: Exception) {
									logger.error(TAG, e) {
										"Failed to send cancellation for $requestId"
									}
								}
							}
						}
					}
				}
			}

			rpcBlock.startCoroutine(continuation)
			COROUTINE_SUSPENDED
		}
		catch (t: Throwable) {
			Dispatchers.Default.dispatch(continuation.context) {
				continuation.intercepted().resumeWithException(t)
			}
			COROUTINE_SUSPENDED
		}
	}

	override suspend fun onReceiveRequest(request: LapisRequest, server: LapisServerService) {
		val job = _scope.launch(LapisRequestInfoContext(getLapisRequestInfo())) {
			val result = server.invokeSuspendMethod()

			logger.debug(TAG) {
				"Received request ${request.requestId} ($request). Result: $result"
			}

			bluetoothDeviceRpc.sendResult(
				requestId = request.requestId,
				result = result,
			)

			bluetoothDeviceRpc.sendEnd(requestId = request.requestId)
		}

		_activeServerJobs[request.requestId] = job

		job.invokeOnCompletion { throwable ->
			when (throwable) {
				is RemoteCancellationException -> {
					// no-op
				}
				is CancellationException -> {
					_scope.launch {

					}
				}
				null -> {
					_activeServerJobs.remove(request.requestId)
				}
			}
		}

		job.join()
	}

	override fun onEnd(requestId: Int) {
		logger.debug(TAG) {
			"Request $requestId finished"
		}
		_pendingContinuationsByRequestId.remove(requestId)
	}

	override fun onCancel(requestId: Int) {
		logger.debug(TAG) {
			"Request $requestId was cancelled"
		}
		_activeServerJobs.remove(requestId)?.cancel(RemoteCancellationException("Remote cancellation from device with address '$deviceAddress'"))
		//_pendingContinuationsByRequestId.remove(requestId)?.resumeWithException(RemoteCancellationException("Remote cancellation from device with address '$deviceAddress'"))
	}

	override fun onResult(requestId: Int, result: Any?) {
		logger.debug(TAG) {
			"Received result for request $requestId: $result"
		}
		_pendingContinuationsByRequestId[requestId]?.resume(result)
	}

	override fun onErrorMessage(requestId: Int, throwable: Throwable) {
		logger.error(TAG) {
			"onErrorMessage($throwable)"
		}
		_pendingContinuationsByRequestId.remove(requestId)?.resumeWithException(throwable)
	}

	override fun onDeviceDisconnected(deviceAddress: BluetoothDevice.Address) {
		logger.error(TAG) {
			"onDeviceDisconnected(${_pendingContinuationsByRequestId.size})"
		}
		_pendingContinuationsByRequestId.forEach { (_, continuation) ->
			continuation.resumeWithException(CancellationException("Device '$deviceAddress' disconnected"))
		}
		_pendingContinuationsByRequestId.clear()

		_activeServerJobs.forEach { (_, job) ->
			job.cancel(CancellationException("Device '$deviceAddress' disconnected"))
		}
		_activeServerJobs.clear()
	}

	override suspend fun onAllRequestsFailed(throwable: Throwable) {
		logger.error(TAG) {
			"onAllRequestsFailed resume with exception: $throwable | ${currentCoroutineContext()}"
		}
		_pendingContinuationsByRequestId.forEach { (_, continuation) ->
			continuation.resumeWithException(throwable)
		}
		_pendingContinuationsByRequestId.clear()

		_activeServerJobs.forEach { (_, job) ->
			job.cancel(CancellationException("Internal error", throwable))
		}
		_activeServerJobs.clear()
	}


	private fun generateId() = _nextId.getAndIncrement()


	companion object {
		private val TAG = SuspendMethodAdapter::class.simpleName!!
	}
}
