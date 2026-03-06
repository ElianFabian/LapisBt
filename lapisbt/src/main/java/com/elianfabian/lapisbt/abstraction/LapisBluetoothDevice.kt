package com.elianfabian.lapisbt.abstraction

import com.elianfabian.lapisbt.annotation.InternalBluetoothReflectionApi
import java.util.UUID

internal interface LapisBluetoothDevice {

	val address: String
	val name: String?
	val alias: String?
	val uuids: List<UUID>?
	val majorDeviceClass: Int
	val addressType: Int
	val type: Int
	val bondState: Int

	fun createRfcommSocketToServiceRecord(uuid: UUID): LapisBluetoothSocket
	fun createInsecureRfcommSocketToServiceRecord(uuid: UUID): LapisBluetoothSocket
	fun createBond(): Boolean

	@InternalBluetoothReflectionApi
	fun removeBond(): Boolean

	@InternalBluetoothReflectionApi
	fun isConnected(): Boolean

	@InternalBluetoothReflectionApi
	fun isEncrypted(): Boolean
}
