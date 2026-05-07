package com.elianfabian.lapisbt_rpc.method_adapter.adapter

import android.util.Log
import com.elianfabian.lapisbt_rpc.LapisRequestInfoContext
import com.elianfabian.lapisbt_rpc.getLapisRequestInfo
import com.elianfabian.lapisbt_rpc.method_adapter.LapisMethodAdapter
import com.elianfabian.lapisbt_rpc.method_adapter.LapisServerService
import com.elianfabian.lapisbt_rpc.method_adapter.MethodCommunicator
import com.elianfabian.lapisbt_rpc.model.LapisRequest
import com.elianfabian.lapisbt_rpc.util.getSuspendReturnType
import com.elianfabian.lapisbt_rpc.util.isSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine
import kotlin.reflect.KClass

internal class SuspendMethodAdapter(
	private val deviceAddress: String,
	private val methodCommunicator: MethodCommunicator,
) : LapisMethodAdapter {

	private val _scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

	private val _pendingContinuationsByRequestId = ConcurrentHashMap<UUID, Continuation<Any?>>()
	private val _activeServerJobs = ConcurrentHashMap<UUID, Job>()


	override fun onRegister() {
		// no-op
	}

	override fun onUnregister() {
		val message = "BluetoothDeviceRpc for '$deviceAddress' is being disposed"

		_scope.cancel(CancellationException(message))

		_activeServerJobs.forEach { (_, job) ->
			job.cancel(CancellationException(message))
		}
		_activeServerJobs.clear()

		_pendingContinuationsByRequestId.forEach { (_, continuation) ->
			continuation.resumeWithException(CancellationException(message))
		}
		_pendingContinuationsByRequestId.clear()
	}

	override fun getOutputType(method: Method): KClass<*> {
		return method.getSuspendReturnType().kotlin
	}

	override fun shouldIntercept(method: Method): Boolean = method.isSuspend()

	override fun functionCall(
		requestId: UUID,
		method: Method,
		args: Array<out Any?>?,
	): Any {
		println("$$$$$ functionCall called with method: ${method.name}, args: ${args?.joinToString()}")

		@Suppress("UNCHECKED_CAST")
		val continuation = args.orEmpty().last() as Continuation<Any?>
		return try {

			val rpcBlock = suspend {
				suspendCancellableCoroutine { cancellableContinuation ->
					_pendingContinuationsByRequestId[requestId] = cancellableContinuation

					cancellableContinuation.invokeOnCancellation { cause ->
						println("$$$ invokeOnCompletion($requestId): $cause")
						_pendingContinuationsByRequestId.remove(requestId)

						if (cause is CancellationException) {
							_scope.launch {
								try {
									methodCommunicator.cancel(requestId = requestId)
								}
								catch (e: Exception) {
									Log.e(TAG, "Failed to send cancellation for $requestId", e)
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

			println("$$$ onReceiveRequest: request = $request, result = $result")

			methodCommunicator.sendResult(
				requestId = request.requestId,
				result = result,
			)

			methodCommunicator.sendEnd(requestId = request.requestId)
		}

		_activeServerJobs[request.requestId] = job

		job.join()
	}

	override fun onEnd(requestId: UUID) {
		println("$$$ onEnd: $requestId")
		_pendingContinuationsByRequestId.remove(requestId)
	}

	override fun onCancel(requestId: UUID) {
		println("$$$ onCancel: $requestId")
		_activeServerJobs.remove(requestId)?.cancel(CancellationException("Remote cancellation from device with address '$deviceAddress'"))
		_pendingContinuationsByRequestId.remove(requestId)?.resumeWithException(CancellationException("Remote cancellation from device with address '$deviceAddress'"))
	}

	override fun onResult(requestId: UUID, result: Any?) {
		println("$$$ onResult($requestId) = $result | ${_pendingContinuationsByRequestId[requestId]}")
		_pendingContinuationsByRequestId[requestId]?.resume(result)
	}

	override fun onErrorMessage(requestId: UUID, throwable: Throwable) {
		_pendingContinuationsByRequestId.remove(requestId)?.resumeWithException(throwable)
	}


	companion object {
		private val TAG = this::class.qualifiedName!!
	}
}
