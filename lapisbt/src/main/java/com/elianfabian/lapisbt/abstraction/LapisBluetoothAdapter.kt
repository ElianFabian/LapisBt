package com.elianfabian.lapisbt.abstraction

import java.util.UUID

internal interface LapisBluetoothAdapter {

	val name: String?
	val isEnabled: Boolean
	val isDiscovering: Boolean

	fun setName(name: String): Boolean
	fun startDiscovery(): Boolean
	fun cancelDiscovery(): Boolean
	fun listenUsingRfcommWithServiceRecord(name: String, uuid: UUID): LapisBluetoothServerSocket
	fun listenUsingInsecureRfcommWithServiceRecord(name: String, uuid: UUID): LapisBluetoothServerSocket
	fun getRemoteDevice(address: String): LapisBluetoothDevice
	fun getBondedDevices(): List<LapisBluetoothDevice>?
}
