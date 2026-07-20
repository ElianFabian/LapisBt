package com.elianfabian.lapisbt.abstraction

import com.elianfabian.lapisbt.annotation.InternalBluetoothReflectionApi
import java.util.UUID

public interface LapisBluetoothDevice {

	public val address: String
	public val name: String?
	public val alias: String?
	public val uuids: List<UUID>?
	public val deviceClass: Int?
	public val majorDeviceClass: Int?
	public val addressType: Int
	public val type: Int
	public val bondState: Int

	public fun createRfcommSocketToServiceRecord(uuid: UUID): LapisBluetoothSocket
	public fun createInsecureRfcommSocketToServiceRecord(uuid: UUID): LapisBluetoothSocket
	public fun createBond(): Boolean

	public fun setAlias(alias: String?): Int

	public fun setPin(pin: ByteArray): Boolean

	public fun fetchUuidsWithSdp(): Boolean

	@InternalBluetoothReflectionApi
	public fun removeBond(): Boolean

	@InternalBluetoothReflectionApi
	public fun cancelBondProcess(): Boolean

	@InternalBluetoothReflectionApi
	public fun isConnected(): Boolean

	@InternalBluetoothReflectionApi
	public fun isEncrypted(): Boolean
}
