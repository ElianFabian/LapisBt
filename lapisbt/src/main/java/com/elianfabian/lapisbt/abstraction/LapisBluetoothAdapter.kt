package com.elianfabian.lapisbt.abstraction

import java.util.UUID

public interface LapisBluetoothAdapter {

	public val name: String?
	public val isEnabled: Boolean
	public val isDiscovering: Boolean

	public fun setName(name: String): Boolean
	public fun startDiscovery(): Boolean
	public fun cancelDiscovery(): Boolean
	public fun listenUsingRfcommWithServiceRecord(name: String, uuid: UUID): LapisBluetoothServerSocket
	public fun listenUsingInsecureRfcommWithServiceRecord(name: String, uuid: UUID): LapisBluetoothServerSocket
	public fun getRemoteDevice(address: String): LapisBluetoothDevice
	public fun getBondedDevices(): List<LapisBluetoothDevice>?
}
