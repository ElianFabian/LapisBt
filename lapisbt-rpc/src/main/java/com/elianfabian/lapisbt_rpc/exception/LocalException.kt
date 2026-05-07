package com.elianfabian.lapisbt_rpc.exception

/**
 * Allows to force throw an exception in the server side and avoid
 * sending it to the client.
 */
internal class LocalException(override val cause: Throwable?) : RuntimeException(cause)

internal fun Throwable.asLocalException() = LocalException(this)
