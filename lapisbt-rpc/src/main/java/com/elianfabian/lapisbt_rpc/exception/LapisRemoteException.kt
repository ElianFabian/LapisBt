package com.elianfabian.lapisbt_rpc.exception

public class LapisRemoteException(
	override val message: String,
) : RuntimeException(message)
