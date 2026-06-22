package com.elianfabian.lapisbt.abstraction.impl

import android.app.Activity
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import com.elianfabian.lapisbt.abstraction.LapisBluetoothDevice
import com.elianfabian.lapisbt.abstraction.LapisBluetoothEvents
import com.elianfabian.lapisbt.broadcast_receiver.BluetoothStateChangeBroadcastReceiver
import com.elianfabian.lapisbt.broadcast_receiver.DeviceAliasChangeBroadcastReceiver
import com.elianfabian.lapisbt.broadcast_receiver.DeviceBondStateChangeBroadcastReceiver
import com.elianfabian.lapisbt.broadcast_receiver.DeviceConnectionStateChangeBroadcastReceiver
import com.elianfabian.lapisbt.broadcast_receiver.DeviceFoundBroadcastReceiver
import com.elianfabian.lapisbt.broadcast_receiver.DeviceNameChangeBroadcastReceiver
import com.elianfabian.lapisbt.broadcast_receiver.DeviceUuidsChangeBroadcastReceiver
import com.elianfabian.lapisbt.broadcast_receiver.DiscoveryStateChangeBroadcastReceiver
import com.elianfabian.lapisbt.broadcast_receiver.PairingRequestBroadcastReceiver
import com.elianfabian.lapisbt.broadcast_receiver.ScanModeChangeBroadcastReceiver
import com.elianfabian.lapisbt.logger.LapisLogger
import com.elianfabian.lapisbt.logger.LapisLogger.Companion.verbose
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class LapisBluetoothEventsImpl(
	private val context: Context,
	private val logger: LapisLogger,
) : LapisBluetoothEvents {

	private val _bluetoothStateFlow = MutableSharedFlow<Int>(extraBufferCapacity = Int.MAX_VALUE)
	override val bluetoothStateFlow = _bluetoothStateFlow.asSharedFlow()

	private val _deviceAliasChangeFlow = MutableSharedFlow<LapisBluetoothDevice>(extraBufferCapacity = Int.MAX_VALUE)
	override val deviceAliasChangeFlow = _deviceAliasChangeFlow.asSharedFlow()

	private val _deviceBondStateChangeFlow = MutableSharedFlow<LapisBluetoothDevice>(extraBufferCapacity = Int.MAX_VALUE)
	override val deviceBondStateChangeFlow = _deviceBondStateChangeFlow.asSharedFlow()

	private val _unbondReasonFlow = MutableSharedFlow<LapisBluetoothEvents.UnbondReasonEvent>(extraBufferCapacity = Int.MAX_VALUE)
	override val unbondReasonFlow = _unbondReasonFlow.asSharedFlow()

	private val _deviceDisconnectedFlow = MutableSharedFlow<LapisBluetoothDevice>(extraBufferCapacity = Int.MAX_VALUE)
	override val deviceDisconnectedFlow: SharedFlow<LapisBluetoothDevice> = _deviceDisconnectedFlow.asSharedFlow()

	private val _deviceNameFlow = MutableSharedFlow<String?>(extraBufferCapacity = Int.MAX_VALUE)
	override val deviceNameFlow = _deviceNameFlow.asSharedFlow()

	private val _deviceUuidsChangeFlow = MutableSharedFlow<LapisBluetoothEvents.UuidsChangeEvent>(extraBufferCapacity = Int.MAX_VALUE)
	override val deviceUuidsChangeFlow = _deviceUuidsChangeFlow.asSharedFlow()

	private val _deviceFoundFlow = MutableSharedFlow<LapisBluetoothEvents.DeviceFoundEvent>(extraBufferCapacity = Int.MAX_VALUE)
	override val deviceFoundFlow = _deviceFoundFlow.asSharedFlow()

	private val _isDiscoveringFlow = MutableSharedFlow<Boolean>(extraBufferCapacity = Int.MAX_VALUE)
	override val isDiscoveringFlow = _isDiscoveringFlow.asSharedFlow()

	private val _scanModeFlow = MutableSharedFlow<Int>(extraBufferCapacity = Int.MAX_VALUE)
	override val scanModeFlow = _scanModeFlow.asSharedFlow()

	private val _onActivityResumed = MutableSharedFlow<Unit>(extraBufferCapacity = Int.MAX_VALUE)
	override val onActivityResumed = _onActivityResumed.asSharedFlow()

	private val _pairingRequestFlow = MutableSharedFlow<LapisBluetoothEvents.PairingRequestEvent>(extraBufferCapacity = Int.MAX_VALUE)
	override val pairingRequestFlow = _pairingRequestFlow.asSharedFlow()


	override fun dispose() {
		logger.verbose(TAG) {
			"dispose()"
		}

		val application = context.applicationContext as Application
		application.unregisterActivityLifecycleCallbacks(_activityLifecycleCallbacks)

		context.unregisterReceiver(_bluetoothStateChangeReceiver)
		if (Build.VERSION.SDK_INT >= 30) {
			context.unregisterReceiver(_deviceAliasChangeReceiver)
		}
		context.unregisterReceiver(_bondStateChangeReceiver)
		context.unregisterReceiver(_deviceConnectionReceiver)
		context.unregisterReceiver(_deviceNameChangeReceiver)
		context.unregisterReceiver(_deviceUuidsChangeReceiver)
		context.unregisterReceiver(_deviceFoundReceiver)
		context.unregisterReceiver(_discoveryStateChangeReceiver)
		context.unregisterReceiver(_scanModeChangeReceiver)
		context.unregisterReceiver(_pairingRequestBroadcastReceiver)
	}


	private val _activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {

		override fun onActivityResumed(activity: Activity) {
			_onActivityResumed.tryEmit(Unit)
		}

		override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
		override fun onActivityPaused(a: Activity) {}
		override fun onActivityStarted(a: Activity) {}
		override fun onActivityStopped(a: Activity) {}
		override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
		override fun onActivityDestroyed(a: Activity) {}
	}

	private val _bluetoothStateChangeReceiver = BluetoothStateChangeBroadcastReceiver(
		onStateChange = { state ->
			logger.verbose(TAG) {
				"onStateChange($state)"
			}
			_bluetoothStateFlow.tryEmit(state)
		}
	)

	private val _deviceAliasChangeReceiver = DeviceAliasChangeBroadcastReceiver(
		onAliasChanged = { androidDevice, _ ->
			if (Build.VERSION.SDK_INT >= 30) {
				logger.verbose(TAG) {
					"onAliasChanged($androidDevice)"
				}
				_deviceAliasChangeFlow.tryEmit(LapisBluetoothDeviceImpl(androidDevice))
			}
		}
	)

	private val _bondStateChangeReceiver = DeviceBondStateChangeBroadcastReceiver(
		onStateChange = { androidDevice, oldState, newState, reason ->
			logger.verbose(TAG) {
				"onStateChange(device: $androidDevice, oldState: $oldState, newState: $newState, reason: $reason)"
			}

			if (newState == oldState) {
				return@DeviceBondStateChangeBroadcastReceiver
			}
			if (newState == AndroidBluetoothDevice.BOND_BONDING) {
				// We ignore the bonding state because it is not reliable
				// the bonding state can be trigger when you're trying to connect to a device
				// or when a device is connecting to you.
				// For this reason we'll use this ACTION_PAIRING_REQUEST to reliably get notified
				// about the bonding state.
				return@DeviceBondStateChangeBroadcastReceiver
			}

			val device = LapisBluetoothDeviceImpl(androidDevice)

			if (reason > 0) {
				_unbondReasonFlow.tryEmit(
					LapisBluetoothEvents.UnbondReasonEvent(
						androidDevice = device,
						reason = reason,
					)
				)
			}

			_deviceBondStateChangeFlow.tryEmit(device)
		}
	)

	private val _deviceConnectionReceiver = DeviceConnectionStateChangeBroadcastReceiver(
		onConnectionStateChange = { androidDevice, isConnected ->
			logger.verbose(TAG) {
				"onConnectionStateChange(device: $androidDevice, isConnected: $isConnected)"
			}
			// When we try to connect to a paired device, this callback executes with isConnected to true and after some small time (around 4s)
			// it executes again with isConnected to false, so the 'true' value here it's not reliable
			// So we only care about the false value
			if (!isConnected) {
				_deviceDisconnectedFlow.tryEmit(LapisBluetoothDeviceImpl(androidDevice))
			}
		}
	)

	private val _deviceNameChangeReceiver = DeviceNameChangeBroadcastReceiver(
		onNameChange = { newName ->
			logger.verbose(TAG) {
				"onNameChange($newName)"
			}
			_deviceNameFlow.tryEmit(newName)
		}
	)

	private val _deviceUuidsChangeReceiver = DeviceUuidsChangeBroadcastReceiver(
		onUuidsChange = { androidDevice, uuids, isTimeout ->
			logger.verbose(TAG) {
				"onUuidsChange(device: $androidDevice, uuids: $uuids, isTimeout: $isTimeout)"
			}
			_deviceUuidsChangeFlow.tryEmit(
				LapisBluetoothEvents.UuidsChangeEvent(
					androidDevice = LapisBluetoothDeviceImpl(androidDevice),
					uuids = uuids,
					isTimeout = isTimeout,
				)
			)
		}
	)

	private val _deviceFoundReceiver = DeviceFoundBroadcastReceiver(
		onDeviceFound = { androidDeviceFound, rssi ->
			logger.verbose(TAG) {
				"onDeviceFound(device: $androidDeviceFound, rssi: $rssi)"
			}
			_deviceFoundFlow.tryEmit(
				LapisBluetoothEvents.DeviceFoundEvent(
					androidDevice = LapisBluetoothDeviceImpl(androidDeviceFound),
					rssi = rssi,
				)
			)
		}
	)

	private val _discoveryStateChangeReceiver = DiscoveryStateChangeBroadcastReceiver(
		onDiscoveryStateChange = { isDiscovering ->
			logger.verbose(TAG) {
				"onDiscoveryStateChange($isDiscovering)"
			}
			_isDiscoveringFlow.tryEmit(isDiscovering)
		}
	)

	private val _scanModeChangeReceiver = ScanModeChangeBroadcastReceiver(
		onScanModeChanged = { previousScanMode, newScanMode ->
			logger.verbose(TAG) {
				"onScanModeChanged(previousScanMode: $previousScanMode, newScanMode: $newScanMode)"
			}
			_scanModeFlow.tryEmit(newScanMode)
		}
	)

	private val _pairingRequestBroadcastReceiver = PairingRequestBroadcastReceiver(
		onPairingRequest = { androidDevice, pairingKey, pairingVariant ->
			logger.verbose(TAG) {
				"onPairingRequest(device: $androidDevice, pairingKey: $pairingKey, pairingVariant: $pairingVariant)"
			}

			val lapisDevice = LapisBluetoothDeviceImpl(androidDevice)

			_pairingRequestFlow.tryEmit(
				LapisBluetoothEvents.PairingRequestEvent(
					androidDevice = lapisDevice,
					pairingKey = pairingKey,
					pairingVariant = pairingVariant,
				)
			)
			_deviceBondStateChangeFlow.tryEmit(lapisDevice)
		}
	)

	// This must be after all the declarations
	init {
		initialize()
	}


	private fun initialize() {
		logger.verbose(TAG) {
			"initialize()"
		}

		val application = context.applicationContext as Application
		application.registerActivityLifecycleCallbacks(_activityLifecycleCallbacks)

		context.registerReceiver(
			_bluetoothStateChangeReceiver,
			IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
		)
		if (Build.VERSION.SDK_INT >= 30) {
			context.registerReceiver(
				_deviceAliasChangeReceiver,
				IntentFilter(AndroidBluetoothDevice.ACTION_ALIAS_CHANGED),
			)
		}
		context.registerReceiver(
			_bondStateChangeReceiver,
			IntentFilter(AndroidBluetoothDevice.ACTION_BOND_STATE_CHANGED),
		)
		context.registerReceiver(
			_deviceConnectionReceiver,
			IntentFilter().apply {
				addAction(AndroidBluetoothDevice.ACTION_ACL_CONNECTED)
				addAction(AndroidBluetoothDevice.ACTION_ACL_DISCONNECTED)
			},
		)
		context.registerReceiver(
			_deviceNameChangeReceiver,
			IntentFilter(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED),
		)
		context.registerReceiver(
			_deviceUuidsChangeReceiver,
			IntentFilter(AndroidBluetoothDevice.ACTION_UUID),
		)
		context.registerReceiver(
			_deviceFoundReceiver,
			IntentFilter(AndroidBluetoothDevice.ACTION_FOUND),
		)
		context.registerReceiver(
			_discoveryStateChangeReceiver,
			IntentFilter().apply {
				addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
				addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
			},
		)
		context.registerReceiver(
			_scanModeChangeReceiver,
			IntentFilter(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED),
		)
		context.registerReceiver(
			_pairingRequestBroadcastReceiver,
			IntentFilter(AndroidBluetoothDevice.ACTION_PAIRING_REQUEST),
		)


		// NOTES:
		// - ACTION_ACL_DISCONNECT_REQUESTED: I haven't observed this action being fired during testing
	}


	companion object {
		private val TAG = LapisBluetoothEventsImpl::class.java.simpleName
	}
}
