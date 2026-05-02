package com.elianfabian.lapisbt_rpc

import com.elianfabian.lapisbt_rpc.model.LapisRequest
import com.elianfabian.lapisbt_rpc.model.LapisResponse

public interface LapisInterceptor {

	public suspend fun interceptIncomingRequest(
		deviceAddress: String,
		request: LapisRequest,
	) {
		// no-op
	}

	public suspend fun interceptIncomingRequestResult(
		deviceAddress: String,
		request: LapisRequest,
		result: Any?,
	) {
		// no-op
	}

	public suspend fun interceptOutgoingRequest(
		deviceAddress: String,
		request: LapisRequest,
	) {
		// no-op
	}

	public suspend fun interceptIncomingResponse(
		deviceAddress: String,
		response: LapisResponse,
	) {
		// no-op
	}

	public suspend fun interceptOutgoingResponse(
		deviceAddress: String,
		response: LapisResponse,
	) {
		// no-op
	}
}


internal object NoOpLapisInterceptor : LapisInterceptor
