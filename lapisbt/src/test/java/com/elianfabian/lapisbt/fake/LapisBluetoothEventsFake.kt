package com.elianfabian.lapisbt.fake

import com.elianfabian.lapisbt.abstraction.LapisBluetoothDevice
import com.elianfabian.lapisbt.abstraction.LapisBluetoothEvents
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class LapisBluetoothEventsFake : LapisBluetoothEvents {

	private val _bluetoothStateFlow = MutableSharedFlow<Int>(extraBufferCapacity = Int.MAX_VALUE)
	override val bluetoothStateFlow = _bluetoothStateFlow.asSharedFlow()

	private val _deviceAliasChangeFlow = MutableSharedFlow<LapisBluetoothDevice>(extraBufferCapacity = Int.MAX_VALUE)
	override val deviceAliasChangeFlow: SharedFlow<LapisBluetoothDevice> = _deviceAliasChangeFlow.asSharedFlow()

	private val _deviceBondStateChangeFlow = MutableSharedFlow<LapisBluetoothDevice>(extraBufferCapacity = Int.MAX_VALUE)
	override val deviceBondStateChangeFlow: SharedFlow<LapisBluetoothDevice> = _deviceBondStateChangeFlow.asSharedFlow()

	private val _deviceDisconnectedFlow = MutableSharedFlow<LapisBluetoothDevice>(extraBufferCapacity = Int.MAX_VALUE)
	override val deviceDisconnectedFlow: SharedFlow<LapisBluetoothDevice> = _deviceDisconnectedFlow.asSharedFlow()

	private val _deviceNameFlow = MutableSharedFlow<String?>(extraBufferCapacity = Int.MAX_VALUE)
	override val deviceNameFlow: SharedFlow<String?> = _deviceNameFlow.asSharedFlow()

	private val _deviceUuidsChangeFlow = MutableSharedFlow<LapisBluetoothDevice>(extraBufferCapacity = Int.MAX_VALUE)
	override val deviceUuidsChangeFlow: SharedFlow<LapisBluetoothDevice> = _deviceUuidsChangeFlow.asSharedFlow()

	private val _deviceFoundFlow = MutableSharedFlow<LapisBluetoothDevice>(extraBufferCapacity = Int.MAX_VALUE)
	override val deviceFoundFlow: SharedFlow<LapisBluetoothDevice> = _deviceFoundFlow.asSharedFlow()

	private val _isDiscoveringFlow = MutableSharedFlow<Boolean>(extraBufferCapacity = Int.MAX_VALUE)
	override val isDiscoveringFlow: SharedFlow<Boolean> = _isDiscoveringFlow.asSharedFlow()

	private val _onActivityResumed = MutableSharedFlow<Unit>(extraBufferCapacity = Int.MAX_VALUE)
	override val onActivityResumed: SharedFlow<Unit> = _onActivityResumed.asSharedFlow()


	fun emitBluetoothState(state: Int) {
		_bluetoothStateFlow.tryEmit(state)
	}

	fun emitDeviceFound(device: LapisBluetoothDevice) {
		_deviceFoundFlow.tryEmit(device)
	}

	fun emitDeviceDisconnected(device: LapisBluetoothDevice) {
		_deviceDisconnectedFlow.tryEmit(device)
	}

	fun emitDeviceBondState(device: LapisBluetoothDevice) {
		_deviceBondStateChangeFlow.tryEmit(device).also {
			println("Emitted bond state change for device ${device.address}, new bond state: ${device.bondState}: $it")
		}
	}

	fun emitDiscovering(isDiscovering: Boolean) {
		_isDiscoveringFlow.tryEmit(isDiscovering)
	}

	fun emitDeviceName(name: String?) {
		_deviceNameFlow.tryEmit(name)
	}

	fun emitDeviceAliasChange(device: LapisBluetoothDevice) {
		_deviceAliasChangeFlow.tryEmit(device)
	}

	fun emitDeviceUuidsChange(device: LapisBluetoothDevice) {
		_deviceUuidsChangeFlow.tryEmit(device)
	}

	fun emitActivityResumed() {
		_onActivityResumed.tryEmit(Unit)
	}


	override fun dispose() {
		// No-op
	}
}
