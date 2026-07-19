package com.elianfabian

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public data class LapisBtRpcConfig(
	/**
	 * When a packet is received for a server service that is not yet registered,
	 * this timeout is used to wait for the server service to be registered.
	 *
	 * If the timeout is reached, we throw an exception.
	 */
	val serverServiceRegistrationTimeout: Duration = 5.seconds,

	/**
	 * When a handshake is initiated, this timeout is used to wait for the handshake to complete.
	 *
	 * If the timeout is reached, we throw an exception.
	 */
	val handshakeTimeout: Duration = 5.seconds,
)
