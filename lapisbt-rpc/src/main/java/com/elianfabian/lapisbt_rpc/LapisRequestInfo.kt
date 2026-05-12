package com.elianfabian.lapisbt_rpc

import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt_rpc.exception.asLocalException
import com.elianfabian.lapisbt_rpc.model.LapisRequest
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.CoroutineContext

public suspend fun getLapisRequestInfo(): LapisRequestInfo {
	return currentCoroutineContext()[LapisRequestInfoContext.Key]?.requestInfo ?: throw IllegalStateException(
		"No RequestInfo found in the current coroutine context. This function can only be called within the execution of a request on the server side and if the LapisBtRpc is properly set up."
	).asLocalException()
}

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
