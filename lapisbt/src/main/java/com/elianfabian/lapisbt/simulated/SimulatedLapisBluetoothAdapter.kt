package com.elianfabian.lapisbt.simulated

import android.bluetooth.BluetoothManager
import android.content.Context
import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.abstraction.LapisBluetoothAdapter
import com.elianfabian.lapisbt.abstraction.LapisBluetoothDevice
import com.elianfabian.lapisbt.abstraction.LapisBluetoothServerSocket
import com.elianfabian.lapisbt.abstraction.impl.LapisBluetoothAdapterImpl
import java.util.UUID

internal class SimulatedLapisBluetoothAdapter(
	private val bluetoothEvents: SimulatedLapisBluetoothEvents,
	private val config: SimulatedBluetoothConfiguration,
	private val environment: SimulatedBluetoothEnvironment,
	context: Context?,
) : LapisBluetoothAdapter {

	private val _realAdapter = if (context != null) {
		LapisBluetoothAdapterImpl(
			adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter,
		)
	}
	else null

	internal var address: String = ""

	private var _name: String? = null

	override val name: String?
		get() {
			return if (_realAdapter != null) {
				_realAdapter.name
			}
			else {
				_name ?: ("Device " + address.takeLast(5))
			}
		}

	override val isEnabled: Boolean
		get() {
			return _realAdapter?.isEnabled ?: (config.bluetoothState == LapisBt.BluetoothState.On)
		}

	private var _isDiscovering: Boolean = false
	override val isDiscovering: Boolean get() = _realAdapter?.isDiscovering ?: _isDiscovering

	override val scanMode: Int
		get() = _realAdapter?.scanMode ?: config.scanMode

	override fun setName(name: String): Boolean {
		if (_realAdapter != null) {
			return _realAdapter.setName(name)
		}

		_name = name
		bluetoothEvents.emitDeviceName(name)

		return true
	}

	override fun startDiscovery(): Boolean {
		if (!isEnabled) {
			println("$$$ enabled")
			return false
		}
		if (!config.isBluetoothScanGranted) {
			println("$$$ granted")
			return false
		}
		if (config.needsLocationForScan && !config.isLocationEnabled) {
			println("$$$ location")
			return false
		}

		_isDiscovering = true
		bluetoothEvents.emitDiscovering(true)

		environment.getScannableDevices(address).forEach { device ->
			bluetoothEvents.emitDeviceFound(device, rssi = -60)
		}

		return true
	}

	override fun cancelDiscovery(): Boolean {
		_isDiscovering = false
		bluetoothEvents.emitDiscovering(false)
		return true
	}

	override fun listenUsingRfcommWithServiceRecord(name: String, uuid: UUID): LapisBluetoothServerSocket {
		val serverSocket = SimulatedLapisBluetoothServerSocket(
			environment = environment,
			address = address,
			serviceUuid = uuid
		)
		environment.registerServer(address, uuid, serverSocket, isSecure = true)
		return serverSocket
	}

	override fun listenUsingInsecureRfcommWithServiceRecord(name: String, uuid: UUID): LapisBluetoothServerSocket {
		val serverSocket = SimulatedLapisBluetoothServerSocket(
			environment = environment,
			address = address,
			serviceUuid = uuid
		)
		environment.registerServer(address, uuid, serverSocket, isSecure = false)
		return serverSocket
	}

	override fun getRemoteDevice(address: String): LapisBluetoothDevice {
		return SimulatedLapisBluetoothDevice(
			address = address,
			name = null,
			bluetoothEvents = bluetoothEvents,
			environment = environment,
			requesterAddress = this.address
		)
	}

	override fun getBondedDevices(): List<LapisBluetoothDevice> {
		return environment.getBondedDevicesFor(address)
	}
}
