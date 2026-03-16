package com.elianfabian.lapisbt.util

import android.bluetooth.BluetoothClass
import com.elianfabian.lapisbt.abstraction.LapisBluetoothDevice
import com.elianfabian.lapisbt.model.BluetoothDevice

internal fun LapisBluetoothDevice.toModel(connectionState: BluetoothDevice.ConnectionState): BluetoothDevice {
	return BluetoothDevice(
		address = this.address,
		name = this.name,
		alias = this.alias,
		addressType = when (this.addressType) {
			AndroidBluetoothDevice.ADDRESS_TYPE_PUBLIC -> BluetoothDevice.AddressType.Public
			AndroidBluetoothDevice.ADDRESS_TYPE_RANDOM -> BluetoothDevice.AddressType.Random
			AndroidBluetoothDevice.ADDRESS_TYPE_ANONYMOUS -> BluetoothDevice.AddressType.Anonymous
			AndroidBluetoothDevice.ADDRESS_TYPE_UNKNOWN -> BluetoothDevice.AddressType.Unknown
			else -> BluetoothDevice.AddressType.NotSupported
		},
		majorDeviceClass = when (this.majorDeviceClass) {
			BluetoothClass.Device.Major.AUDIO_VIDEO -> BluetoothDevice.MajorDeviceClass.AudioVideo
			BluetoothClass.Device.Major.COMPUTER -> BluetoothDevice.MajorDeviceClass.Computer
			BluetoothClass.Device.Major.HEALTH -> BluetoothDevice.MajorDeviceClass.Health
			BluetoothClass.Device.Major.IMAGING -> BluetoothDevice.MajorDeviceClass.Imaging
			BluetoothClass.Device.Major.WEARABLE -> BluetoothDevice.MajorDeviceClass.Wearable
			BluetoothClass.Device.Major.MISC -> BluetoothDevice.MajorDeviceClass.Misc
			BluetoothClass.Device.Major.PHONE -> BluetoothDevice.MajorDeviceClass.Phone
			BluetoothClass.Device.Major.NETWORKING -> BluetoothDevice.MajorDeviceClass.Networking
			BluetoothClass.Device.Major.TOY -> BluetoothDevice.MajorDeviceClass.Toy
			BluetoothClass.Device.Major.PERIPHERAL -> BluetoothDevice.MajorDeviceClass.Peripheral
			BluetoothClass.Device.Major.UNCATEGORIZED -> BluetoothDevice.MajorDeviceClass.Uncategorized
			else -> BluetoothDevice.MajorDeviceClass.Uncategorized
		},
		type = when (this.type) {
			AndroidBluetoothDevice.DEVICE_TYPE_CLASSIC -> BluetoothDevice.Type.Classic
			AndroidBluetoothDevice.DEVICE_TYPE_LE -> BluetoothDevice.Type.Le
			AndroidBluetoothDevice.DEVICE_TYPE_DUAL -> BluetoothDevice.Type.Dual
			AndroidBluetoothDevice.DEVICE_TYPE_UNKNOWN -> BluetoothDevice.Type.Unknown
			else -> BluetoothDevice.Type.Unknown
		},
		uuids = this.uuids,
		pairingState = when (this.bondState) {
			AndroidBluetoothDevice.BOND_BONDED -> BluetoothDevice.PairingState.Paired
			AndroidBluetoothDevice.BOND_BONDING -> BluetoothDevice.PairingState.Pairing
			AndroidBluetoothDevice.BOND_NONE -> BluetoothDevice.PairingState.None
			else -> BluetoothDevice.PairingState.None
		},
		connectionState = connectionState,
	)
}
