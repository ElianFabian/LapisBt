package com.elianfabian.lapisbt.fake

import android.bluetooth.BluetoothManager
import android.content.Context
import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.abstraction.LapisBluetoothAdapter
import com.elianfabian.lapisbt.abstraction.LapisBluetoothDevice
import com.elianfabian.lapisbt.abstraction.LapisBluetoothServerSocket
import java.util.UUID

internal class LapisBluetoothAdapterFake(
	private val bluetoothEventsFake: LapisBluetoothEventsFake,
	private val config: FakeBluetoothConfiguration,
	private val environment: FakeBluetoothEnvironment,
    private val context: Context?,
) : LapisBluetoothAdapter {

	internal var address: String = ""

	private var _name: String? = null

	override val name: String? get() {
        return if (context != null) {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothManager.adapter.name
        } else {
            _name ?: config.name
        }
    }

	override val isEnabled: Boolean get() {
        return if (context != null) {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bluetoothManager.adapter.isEnabled
        } else {
            config.bluetoothState == LapisBt.BluetoothState.On
        }
	}

	private var _isDiscovering: Boolean = false
	override val isDiscovering: Boolean get() = _isDiscovering

	override fun setName(name: String): Boolean {
		_name = name
		bluetoothEventsFake.emitDeviceName(name)
		return true
	}

	override fun startDiscovery(): Boolean {
		if (!isEnabled) {
			return false
		}
        
        if (context != null) {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val realAdapter = bluetoothManager.adapter
            val success = realAdapter.startDiscovery()
            if (!success) return false
            realAdapter.cancelDiscovery()
        } else {
            if (!config.isBluetoothScanGranted) return false
            if (config.needsLocationForScan && !config.isLocationEnabled) return false
        }

		_isDiscovering = true
		bluetoothEventsFake.emitDiscovering(true)

		environment.getScannableDevices(address).forEach { device ->
			bluetoothEventsFake.emitDeviceFound(device)
		}

		return true
	}

	override fun cancelDiscovery(): Boolean {
		_isDiscovering = false
		bluetoothEventsFake.emitDiscovering(false)
		return true
	}

	override fun listenUsingRfcommWithServiceRecord(name: String, uuid: UUID): LapisBluetoothServerSocket {
		val serverSocket = LapisBluetoothServerSocketFake(
			environment = environment,
			address = address,
			serviceUuid = uuid
		)
		environment.registerServer(address, uuid, serverSocket, isSecure = true)
		return serverSocket
	}

	override fun listenUsingInsecureRfcommWithServiceRecord(name: String, uuid: UUID): LapisBluetoothServerSocket {
		val serverSocket = LapisBluetoothServerSocketFake(
			environment = environment,
			address = address,
			serviceUuid = uuid
		)
		environment.registerServer(address, uuid, serverSocket, isSecure = false)
		return serverSocket
	}

	override fun getRemoteDevice(address: String): LapisBluetoothDevice {
		return LapisBluetoothDeviceFake(
			address = address,
			name = null,
			bluetoothEventsFake = bluetoothEventsFake,
			environment = environment,
			requesterAddress = this.address
		)
	}

	override fun getBondedDevices(): List<LapisBluetoothDevice>? {
		return environment.getBondedDevicesFor(address)
	}
}
