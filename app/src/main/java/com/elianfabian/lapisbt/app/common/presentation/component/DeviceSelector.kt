package com.elianfabian.lapisbt.app.common.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elianfabian.lapisbt.app.common.util.simplestack.compose.BasePreview
import com.elianfabian.lapisbt.model.BluetoothDevice

sealed interface DeviceSelection {
	data class Device(val device: BluetoothDevice) : DeviceSelection
	data object AllDevices : DeviceSelection
	data object None : DeviceSelection
}

@Composable
fun DeviceSelector(
	connectedDevices: List<BluetoothDevice>,
	deviceSelection: DeviceSelection,
	onSelectDevice: (BluetoothDevice) -> Unit,
	onSelectAll: (() -> Unit)? = null,
	modifier: Modifier = Modifier,
) {
	var isExpanded by remember { mutableStateOf(false) }

	Column(modifier = modifier) {
		if (connectedDevices.isEmpty()) {
			Text(text = "No connected devices", modifier = Modifier.padding(8.dp))
		}
		else {
			Card(
				onClick = { isExpanded = true },
			) {
				Column(
					verticalArrangement = Arrangement.Center,
					modifier = Modifier
						.fillMaxWidth()
						.padding(8.dp)
				) {
					when (deviceSelection) {
						is DeviceSelection.AllDevices -> {
							Text(
								text = "All devices",
								fontWeight = FontWeight.Bold,
							)
						}
						is DeviceSelection.Device -> {
							val device = deviceSelection.device
							Text(text = device.name ?: "(No name)")
							Spacer(Modifier.height(4.dp))
							Text(text = device.address.value, fontSize = 12.sp)
						}
						is DeviceSelection.None -> {
							Text(text = "No selected device (Select target)")
						}
					}
				}
			}
		}

		DropdownMenu(
			expanded = isExpanded,
			onDismissRequest = { isExpanded = false },
			modifier = Modifier.fillMaxWidth(0.9f)
		) {
			connectedDevices.forEach { device ->
				DropdownMenuItem(
					text = { Text(text = device.name ?: device.address.value) },
					onClick = {
						onSelectDevice(device)
						isExpanded = false
					},
				)
			}
			if (onSelectAll != null && connectedDevices.isNotEmpty()) {
				DropdownMenuItem(
					text = {
						Text(
							text = "All devices",
							fontWeight = FontWeight.Bold,
						)
					},
					onClick = {
						onSelectAll()
						isExpanded = false
					},
				)
			}
		}
	}
}

@Preview(showBackground = true)
@Composable
private fun DeviceSelectorPreview() = BasePreview {
	val devices = listOf(
		BluetoothDevice(
			name = "Device 1",
			alias = "Device 1",
			type = BluetoothDevice.Type.Unknown,
			deviceClass = BluetoothDevice.DeviceClass.Phone.Smart,
			majorDeviceClass = BluetoothDevice.MajorDeviceClass.Phone,
			uuids = emptyList(),
			addressType = BluetoothDevice.AddressType.Unknown,
			address = BluetoothDevice.Address("00:11:22:33:44:55"),
			connectionState = BluetoothDevice.ConnectionState.Connected,
			pairingState = BluetoothDevice.PairingState.Paired,
		),
		BluetoothDevice(
			name = "Device 2",
			alias = "Device 2",
			type = BluetoothDevice.Type.Unknown,
			deviceClass = BluetoothDevice.DeviceClass.Phone.Smart,
			majorDeviceClass = BluetoothDevice.MajorDeviceClass.Phone,
			uuids = emptyList(),
			addressType = BluetoothDevice.AddressType.Unknown,
			address = BluetoothDevice.Address("AA:BB:CC:DD:EE:FF"),
			connectionState = BluetoothDevice.ConnectionState.Connected,
			pairingState = BluetoothDevice.PairingState.Paired,
		)
	)

	Column(
		verticalArrangement = Arrangement.spacedBy(16.dp),
		modifier = Modifier.padding(16.dp)
	) {
		Text("None Selected:")
		DeviceSelector(
			connectedDevices = devices,
			deviceSelection = DeviceSelection.None,
			onSelectDevice = {}
		)

		Text("Device Selected:")
		DeviceSelector(
			connectedDevices = devices,
			deviceSelection = DeviceSelection.Device(devices[0]),
			onSelectDevice = {}
		)

		Text("All Devices (with Select All):")
		DeviceSelector(
			connectedDevices = devices,
			deviceSelection = DeviceSelection.AllDevices,
			onSelectDevice = {},
			onSelectAll = {}
		)
	}
}
