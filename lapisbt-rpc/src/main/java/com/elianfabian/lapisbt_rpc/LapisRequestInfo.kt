package com.elianfabian.lapisbt_rpc

import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt_rpc.exception.asLocalException
import com.elianfabian.lapisbt_rpc.model.LapisRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

/**
 * Retrieves information about the current RPC request from the coroutine context.
 *
 * This function must be called within the execution of an RPC method on the server side.
 * It provides access to the calling device's address and the original request details.
 *
 * @throws IllegalStateException If called outside an RPC method execution.
 */
public suspend fun getLapisRequestInfo(): LapisRequestInfo {
	return currentCoroutineContext()[LapisRequestInfoContext.Key]?.requestInfo ?: throw IllegalStateException(
		"No RequestInfo found in the current coroutine context. This function can only be called within the execution of a request on the server side and if the ${LapisBtRpc::class.simpleName} is properly set up."
	).asLocalException()
}

/**
 * Contains information about an incoming RPC request.
 *
 * @property deviceAddress The address of the remote device that sent the request.
 * @property request The original request object containing method name, arguments, and metadata.
 */
public data class LapisRequestInfo(
	public val deviceAddress: BluetoothDevice.Address,
	public val request: LapisRequest,
)


internal class LapisRequestInfoContext(
	internal val requestInfo: LapisRequestInfo,
) : CoroutineContext.Element {

	override val key get() = Key

	companion object Key : CoroutineContext.Key<LapisRequestInfoContext>
}
