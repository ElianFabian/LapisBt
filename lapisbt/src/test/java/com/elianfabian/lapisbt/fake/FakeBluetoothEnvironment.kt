package com.elianfabian.lapisbt.fake

import com.elianfabian.lapisbt.LapisBtImpl
import com.elianfabian.lapisbt.abstraction.LapisBluetoothDevice
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt.util.BidirectionalStreamPipe
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

internal class FakeBluetoothEnvironment(seed: Long = 1L) {

    private val _random = Random(seed)
    private val _devices = ConcurrentHashMap<String, FakeBluetoothDeviceEntry>()
    private val _activeServers = ConcurrentHashMap<String, MutableMap<UUID, LapisBluetoothServerSocketFake>>()

    data class FakeBluetoothDeviceEntry(
        val device: FakeBluetoothDevice,
        val adapter: LapisBluetoothAdapterFake,
        val events: LapisBluetoothEventsFake
    )

    fun createDevice(
        address: String = generateAddress(),
        name: String = "Device ${address.takeLast(5)}"
    ): FakeBluetoothDevice {
        val eventsFake = LapisBluetoothEventsFake()
        val adapterFake = LapisBluetoothAdapterFake(eventsFake, isEnabled = true, environment = this)
        adapterFake.address = address
        adapterFake.setName(name)

        val androidHelperFake = AndroidHelperFake(
            isBluetoothSupportedResult = true,
            isBluetoothConnectGrantedResult = true,
            isBluetoothScanGrantedResult = true
        )

        val lapisBt = LapisBtImpl(
            lapisAdapter = adapterFake,
            androidHelper = androidHelperFake,
            bluetoothEvents = eventsFake
        )

        val fakeDevice = FakeBluetoothDevice(BluetoothDevice.Address(address), lapisBt)
        val entry = FakeBluetoothDeviceEntry(fakeDevice, adapterFake, eventsFake)
        _devices[address] = entry
        return fakeDevice
    }

    private fun generateAddress(): String {
        return List(6) { _random.nextInt(0, 256) }.joinToString(":") { "%02X".format(it) }
    }

    fun getScannableDevices(requesterAddress: String): List<LapisBluetoothDevice> {
        return _devices.values
            .filter { it.device.address.value != requesterAddress }
            .map { entry ->
                LapisBluetoothDeviceFake(
                    address = entry.device.address.value,
                    name = entry.device.name,
                    bluetoothEventsFake = entry.events,
                    environment = this,
                    requesterAddress = requesterAddress
                )
            }
    }

    fun registerServer(deviceAddress: String, uuid: UUID, socket: LapisBluetoothServerSocketFake) {
        _activeServers.getOrPut(deviceAddress) { ConcurrentHashMap() }[uuid] = socket
    }

    fun unregisterServer(deviceAddress: String, uuid: UUID) {
        _activeServers[deviceAddress]?.remove(uuid)
    }

    fun requestConnection(
        requesterAddress: String,
        targetAddress: String,
        uuid: UUID
    ): LapisBluetoothSocketFake? {
        val serverSocket = _activeServers[targetAddress]?.get(uuid) ?: return null
        
        val requesterEntry = _devices[requesterAddress] ?: return null
        val targetEntry = _devices[targetAddress] ?: return null

        val pipe = BidirectionalStreamPipe()

        val clientSocket = LapisBluetoothSocketFake(
            remoteDevice = LapisBluetoothDeviceFake(
                address = targetAddress,
                name = targetEntry.device.name,
                bluetoothEventsFake = requesterEntry.events,
                environment = this,
                requesterAddress = requesterAddress
            ),
            inputStream = pipe.sideA.inputStream,
            outputStream = pipe.sideA.outputStream
        )

        val serverSideSocket = LapisBluetoothSocketFake(
            remoteDevice = LapisBluetoothDeviceFake(
                address = requesterAddress,
                name = requesterEntry.device.name,
                bluetoothEventsFake = targetEntry.events,
                environment = this,
                requesterAddress = targetAddress
            ),
            inputStream = pipe.sideB.inputStream,
            outputStream = pipe.sideB.outputStream
        )

        clientSocket.twin = serverSideSocket
        serverSideSocket.twin = clientSocket

        serverSocket.enqueueIncomingConnection(serverSideSocket)
        
        return clientSocket
    }
}
