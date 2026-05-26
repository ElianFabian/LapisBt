package com.elianfabian.lapisbt.abstraction

public interface LapisBluetoothServerSocket {

	public fun accept(): LapisBluetoothSocket
	public fun close()
}
