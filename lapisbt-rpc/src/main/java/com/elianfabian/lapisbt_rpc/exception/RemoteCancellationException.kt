package com.elianfabian.lapisbt_rpc.exception

import kotlinx.coroutines.CancellationException

internal class RemoteCancellationException(message: String) : CancellationException(message)
