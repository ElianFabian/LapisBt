package com.elianfabian.lapisbt.simulated

import android.content.Context
import com.elianfabian.lapisbt.abstraction.LapisBluetoothDevice
import com.elianfabian.lapisbt.abstraction.LapisBluetoothEvents
import com.elianfabian.lapisbt.abstraction.impl.LapisBluetoothEventsImpl
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class SimulatedLapisBluetoothEvents(
	context: Context? = null,
) : LapisBluetoothEvents {

	private val _realEvents: LapisBluetoothEvents? = if (context != null) {
		LapisBluetoothEventsImpl(context)
	}
	else null

	private val _bluetoothStateFlow = MutableSharedFlow<Int>(extraBufferCapacity = Int.MAX_VALUE)
	override val bluetoothStateFlow: SharedFlow<Int> = _realEvents?.bluetoothStateFlow ?: _bluetoothStateFlow.asSharedFlow()

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

	private val _deviceFoundFlow = MutableSharedFlow<LapisBluetoothEvents.DeviceFoundEvent>(extraBufferCapacity = Int.MAX_VALUE)
	override val deviceFoundFlow: SharedFlow<LapisBluetoothEvents.DeviceFoundEvent> = _deviceFoundFlow.asSharedFlow()

	private val _isDiscoveringFlow = MutableSharedFlow<Boolean>(extraBufferCapacity = Int.MAX_VALUE)
	override val isDiscoveringFlow: SharedFlow<Boolean> = _isDiscoveringFlow.asSharedFlow()

	private val _scanModeFlow = MutableSharedFlow<Int>(extraBufferCapacity = Int.MAX_VALUE)
	override val scanModeFlow = _realEvents?.scanModeFlow ?: _scanModeFlow.asSharedFlow()

	private val _unbondReasonFlow = MutableSharedFlow<LapisBluetoothEvents.UnbondReasonEvent>(extraBufferCapacity = Int.MAX_VALUE)
	override val unbondReasonFlow: SharedFlow<LapisBluetoothEvents.UnbondReasonEvent> = _unbondReasonFlow.asSharedFlow()

	private val _pairingRequestFlow = MutableSharedFlow<LapisBluetoothEvents.PairingRequestEvent>(extraBufferCapacity = Int.MAX_VALUE)
	override val pairingRequestFlow: SharedFlow<LapisBluetoothEvents.PairingRequestEvent> = _pairingRequestFlow.asSharedFlow()

	private val _onActivityResumed = MutableSharedFlow<Unit>(extraBufferCapacity = Int.MAX_VALUE)
	override val onActivityResumed: SharedFlow<Unit> = _realEvents?.onActivityResumed ?: _onActivityResumed.asSharedFlow()


	fun emitBluetoothState(state: Int) {
		_bluetoothStateFlow.tryEmit(state)
	}

	fun emitDeviceFound(device: LapisBluetoothDevice, rssi: Short = 0) {
		_deviceFoundFlow.tryEmit(LapisBluetoothEvents.DeviceFoundEvent(device, rssi))
	}

	fun emitDeviceDisconnected(device: LapisBluetoothDevice) {
		_deviceDisconnectedFlow.tryEmit(device)
	}

	fun emitDeviceBondState(device: LapisBluetoothDevice) {
		_deviceBondStateChangeFlow.tryEmit(device)
	}

	fun emitDiscovering(isDiscovering: Boolean) {
		_isDiscoveringFlow.tryEmit(isDiscovering)
	}

	fun emitScanMode(scanMode: Int) {
		_scanModeFlow.tryEmit(scanMode)
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

	fun emitPairingRequestEvent(event: LapisBluetoothEvents.PairingRequestEvent) {
		_pairingRequestFlow.tryEmit(event)
	}

	fun emitUnbondReason(event: LapisBluetoothEvents.UnbondReasonEvent) {
		_unbondReasonFlow.tryEmit(event)
	}

	override fun dispose() {
		_realEvents?.dispose()
	}
}
