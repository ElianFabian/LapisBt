package com.elianfabian.lapisbt.util

import android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO
import android.bluetooth.BluetoothClass.Device.Major.COMPUTER
import android.bluetooth.BluetoothClass.Device.Major.HEALTH
import android.bluetooth.BluetoothClass.Device.Major.IMAGING
import android.bluetooth.BluetoothClass.Device.Major.MISC
import android.bluetooth.BluetoothClass.Device.Major.NETWORKING
import android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL
import android.bluetooth.BluetoothClass.Device.Major.PHONE
import android.bluetooth.BluetoothClass.Device.Major.TOY
import android.bluetooth.BluetoothClass.Device.Major.WEARABLE
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.BOND_BONDING
import android.bluetooth.BluetoothDevice.BOND_NONE
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
			AUDIO_VIDEO -> BluetoothDevice.MajorDeviceClass.AudioVideo
			COMPUTER -> BluetoothDevice.MajorDeviceClass.Computer
			HEALTH -> BluetoothDevice.MajorDeviceClass.Health
			IMAGING -> BluetoothDevice.MajorDeviceClass.Imaging
			WEARABLE -> BluetoothDevice.MajorDeviceClass.Wearable
			MISC -> BluetoothDevice.MajorDeviceClass.Misc
			PHONE -> BluetoothDevice.MajorDeviceClass.Phone
			NETWORKING -> BluetoothDevice.MajorDeviceClass.Networking
			TOY -> BluetoothDevice.MajorDeviceClass.Toy
			PERIPHERAL -> BluetoothDevice.MajorDeviceClass.Peripheral
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
			BOND_BONDED -> BluetoothDevice.PairingState.Paired
			BOND_BONDING -> BluetoothDevice.PairingState.Pairing
			BOND_NONE -> BluetoothDevice.PairingState.None
			else -> BluetoothDevice.PairingState.None
		},
		connectionState = connectionState,
	)
}
