package com.elianfabian.lapisbt.fake

import com.elianfabian.lapisbt.abstraction.LapisBluetoothDevice
import com.elianfabian.lapisbt.abstraction.LapisBluetoothEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

internal class LapisBluetoothEventsFake : LapisBluetoothEvents {

	private val _scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

	private val _bluetoothStateFlow = MutableSharedFlow<Int>()
	override val bluetoothStateFlow = _bluetoothStateFlow.asSharedFlow()

	private val _deviceAliasChangeFlow = MutableSharedFlow<LapisBluetoothDevice>()
	override val deviceAliasChangeFlow: SharedFlow<LapisBluetoothDevice> = _deviceAliasChangeFlow.asSharedFlow()

	private val _deviceBondStateChangeFlow = MutableSharedFlow<LapisBluetoothDevice>()
	override val deviceBondStateChangeFlow: SharedFlow<LapisBluetoothDevice> = _deviceBondStateChangeFlow.asSharedFlow()

	private val _deviceDisconnectedFlow = MutableSharedFlow<LapisBluetoothDevice>()
	override val deviceDisconnectedFlow: SharedFlow<LapisBluetoothDevice> = _deviceDisconnectedFlow.asSharedFlow()

	private val _deviceNameFlow = MutableSharedFlow<String?>()
	override val deviceNameFlow: SharedFlow<String?> = _deviceNameFlow.asSharedFlow()

	private val _deviceUuidsChangeFlow = MutableSharedFlow<LapisBluetoothDevice>()
	override val deviceUuidsChangeFlow: SharedFlow<LapisBluetoothDevice> = _deviceUuidsChangeFlow.asSharedFlow()

	private val _deviceFoundFlow = MutableSharedFlow<LapisBluetoothDevice>()
	override val deviceFoundFlow: SharedFlow<LapisBluetoothDevice> = _deviceFoundFlow.asSharedFlow()

	private val _isDiscoveringFlow = MutableSharedFlow<Boolean>()
	override val isDiscoveringFlow: SharedFlow<Boolean> = _isDiscoveringFlow.asSharedFlow()

	private val _onActivityResumed = MutableSharedFlow<Unit>()
	override val onActivityResumed: SharedFlow<Unit> = _onActivityResumed.asSharedFlow()


	fun emitBluetoothState(state: Int) {
		_scope.launch {
			_bluetoothStateFlow.emit(state)
		}
	}

	fun emitDeviceFound(device: LapisBluetoothDevice) {
		_scope.launch {
			_deviceFoundFlow.emit(device)
		}
	}

	fun emitDeviceDisconnected(device: LapisBluetoothDevice) {
		_scope.launch {
			_deviceDisconnectedFlow.emit(device)
		}
	}

	fun emitDeviceBondState(device: LapisBluetoothDevice) {
		_scope.launch {
			_deviceBondStateChangeFlow.emit(device)
		}
	}

	fun emitDiscovering(isDiscovering: Boolean) {
		_scope.launch {
			_isDiscoveringFlow.emit(isDiscovering)
		}
	}

	fun emitDeviceName(name: String?) {
		_scope.launch {
			_deviceNameFlow.emit(name)
		}
	}

	fun emitDeviceAliasChange(device: LapisBluetoothDevice) {
		_scope.launch {
			_deviceAliasChangeFlow.emit(device)
		}
	}

	fun emitDeviceUuidsChange(device: LapisBluetoothDevice) {
		_scope.launch {
			_deviceUuidsChangeFlow.emit(device)
		}
	}

	fun emitActivityResumed() {
		_scope.launch {
			_onActivityResumed.emit(Unit)
		}
	}


	override fun dispose() {
		// No-op
	}
}
