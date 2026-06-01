package com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elianfabian.lapisbt.app.common.presentation.component.BluetoothControlPanel
import com.elianfabian.lapisbt.app.common.presentation.component.BluetoothDeviceItem
import com.elianfabian.lapisbt.app.common.presentation.component.DeviceSelector
import com.elianfabian.lapisbt.app.common.presentation.component.PermissionDialog
import com.elianfabian.lapisbt.app.common.util.simplestack.compose.BasePreview

@Composable
fun ApiBasedBluetoothCommunicationScreen(
	state: ApiBasedBluetoothCommunicationState,
	onAction: (action: ApiBasedBluetoothCommunicationAction) -> Unit,
) {
	state.permissionDialog?.let { dialogState ->
		PermissionDialog(
			state = dialogState
		)
	}

	if (!state.isBluetoothSupported) {
		Column(
			verticalArrangement = Arrangement.Center,
			horizontalAlignment = Alignment.CenterHorizontally,
			modifier = Modifier
				.fillMaxSize()
				.padding(all = 16.dp)
		) {
			Text(
				text = "Your device does not support Bluetooth.",
				fontSize = 18.sp,
				fontWeight = FontWeight.Bold,
			)
		}
	} else {
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(WindowInsets.statusBars.asPaddingValues())
		) {
			BluetoothDeviceList(
				state = state,
				onAction = onAction,
				modifier = Modifier
					.fillMaxSize()
					.weight(1F)
					.padding(horizontal = 16.dp)
			)
			Column(
				modifier = Modifier
					.clip(RoundedCornerShape(8.dp))
					.background(MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp))
					.padding(horizontal = 8.dp)
					.padding(top = 8.dp, bottom = 3.dp)
			) {
				DeviceSelector(
					connectedDevices = state.connectedDevices,
					deviceSelection = state.deviceSelection,
					onSelectDevice = { onAction(ApiBasedBluetoothCommunicationAction.SelectTargetDeviceToMessage(it)) },
					modifier = Modifier.padding(bottom = 8.dp)
				)

				BluetoothControlPanel(
					isScanning = state.isScanning,
					isWaitingForConnection = state.isWaitingForConnection,
					useSecureConnection = state.useSecureConnection,
					onToggleScan = {
						if (state.isScanning) onAction(ApiBasedBluetoothCommunicationAction.StopScan)
						else onAction(ApiBasedBluetoothCommunicationAction.StartScan)
					},
					onToggleServer = {
						if (state.isWaitingForConnection) onAction(ApiBasedBluetoothCommunicationAction.StopServer)
						else onAction(ApiBasedBluetoothCommunicationAction.StartServer)
					},
					onCheckSecureConnection = { onAction(ApiBasedBluetoothCommunicationAction.CheckUseSecureConnection(it)) },
					modifier = Modifier.padding(WindowInsets.navigationBars.asPaddingValues())
				)
			}
		}
	}
}

@Composable
private fun BluetoothDeviceList(
	state: ApiBasedBluetoothCommunicationState,
	onAction: (action: ApiBasedBluetoothCommunicationAction) -> Unit,
	modifier: Modifier = Modifier,
) {
	val haptics = LocalHapticFeedback.current

	LazyColumn(
		verticalArrangement = Arrangement.spacedBy(8.dp),
		contentPadding = PaddingValues(bottom = 16.dp),
		modifier = modifier
	) {
		item {
			Column {
				Spacer(Modifier.height(8.dp))

				if (state.bluetoothDeviceName != null) {
					if (state.enteredBluetoothDeviceName != null) {
						Row(
							verticalAlignment = Alignment.CenterVertically,
						) {
							TextField(
								value = state.enteredBluetoothDeviceName,
								onValueChange = { value ->
									onAction(ApiBasedBluetoothCommunicationAction.EnterBluetoothDeviceName(value))
								},
								modifier = Modifier.weight(1F)
							)
							Spacer(Modifier.width(6.dp))
							IconButton(
								onClick = {
									onAction(ApiBasedBluetoothCommunicationAction.SaveBluetoothDeviceName)
									haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
								},
							) {
								Icon(
									imageVector = Icons.Filled.CheckCircle,
									contentDescription = null,
								)
							}
						}
					} else {
						Row(
							verticalAlignment = Alignment.CenterVertically,
						) {
							Text(
								text = "Your device name: '${state.bluetoothDeviceName}'",
								fontSize = 16.sp,
								modifier = Modifier.weight(1F)
							)
							if (state.isBluetoothOn) {
								IconButton(
									onClick = {
										onAction(ApiBasedBluetoothCommunicationAction.EditBluetoothDeviceName)
										haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
									},
								) {
									Icon(
										imageVector = Icons.Default.Edit,
										contentDescription = null,
									)
								}
							}
						}
					}
				}
				Text(
					text = "Your device address: '${state.currentDeviceAddress ?: "unknown"}'",
					fontSize = 16.sp,
				)
				Spacer(Modifier.height(8.dp))
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Button(onClick = { onAction(ApiBasedBluetoothCommunicationAction.MakeDeviceDiscoverable) }) {
						Text("Discoverable")
					}
					Button(onClick = { onAction(ApiBasedBluetoothCommunicationAction.OpenBluetoothSettings) }) {
						Text("BT Settings")
					}
				}
			}
		}

		if (!state.isBluetoothOn) {
			item {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.padding(vertical = 32.dp),
					horizontalAlignment = Alignment.CenterHorizontally
				) {
					Button(onClick = { onAction(ApiBasedBluetoothCommunicationAction.EnableBluetooth) }) {
						Icon(Icons.Filled.Bluetooth, contentDescription = null)
						Spacer(Modifier.width(8.dp))
						Text("Enable Bluetooth")
					}
				}
			}
		} else {
			// RPC PLAYGROUND
			item {
				Text(
					text = "RPC Playground",
					fontWeight = FontWeight.Bold,
					fontSize = 22.sp,
					modifier = Modifier.padding(top = 8.dp)
				)
			}

			item {
				RpcCategory("Hardware Control") {
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text("Continuous Vibrate", modifier = Modifier.weight(1f))
						VibrationButton(onAction)
					}
					Spacer(Modifier.height(8.dp))
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text("Flashlight", modifier = Modifier.weight(1f))
						Switch(
							checked = state.rpcTestState.flashlightEnabled,
							onCheckedChange = { onAction(ApiBasedBluetoothCommunicationAction.ToggleFlashlight(it)) }
						)
					}
				}
			}

			item {
				RpcCategory("Info & UI") {
					var toastMessage by remember { mutableStateOf("Hello from RPC!") }
					TextField(
						value = toastMessage,
						onValueChange = { toastMessage = it },
						label = { Text("Toast Message") },
						modifier = Modifier.fillMaxWidth()
					)
					Box(modifier = Modifier.fillMaxWidth()) {
						Button(
							onClick = { onAction(ApiBasedBluetoothCommunicationAction.ClickShowToastRemotely(toastMessage)) },
							modifier = Modifier
								.align(Alignment.CenterEnd)
								.padding(top = 4.dp)
						) {
							Text("Send Toast")
						}
					}
					Spacer(Modifier.height(8.dp))
					Button(
						onClick = { onAction(ApiBasedBluetoothCommunicationAction.ClickGetMyOwnAddress) },
						modifier = Modifier.fillMaxWidth()
					) {
						Text("Get Remote Address")
					}
				}
			}

			item {
				RpcCategory("Sensors & Streams") {
					FlowControl(
						label = "Light Sensor",
						isActive = state.rpcTestState.activeFlows.contains("lightSensor"),
						value = state.rpcTestState.latestValues["lightSensor"],
						onStart = { onAction(ApiBasedBluetoothCommunicationAction.StartLightSensor) },
						onStop = { onAction(ApiBasedBluetoothCommunicationAction.StopLightSensor) }
					)
					Spacer(Modifier.height(8.dp))
					FlowControl(
						label = "Random Numbers (500ms)",
						isActive = state.rpcTestState.activeFlows.contains("randomNumbers"),
						value = state.rpcTestState.latestValues["randomNumbers"],
						onStart = { onAction(ApiBasedBluetoothCommunicationAction.StartRandomNumbers(500)) },
						onStop = { onAction(ApiBasedBluetoothCommunicationAction.StopRandomNumbers) }
					)
					Spacer(Modifier.height(8.dp))
					FlowControl(
						label = "Bidirectional (Doubling)",
						isActive = state.rpcTestState.activeFlows.contains("processDataStream"),
						value = state.rpcTestState.latestValues["processDataStream"],
						onStart = { onAction(ApiBasedBluetoothCommunicationAction.StartProcessDataStream) },
						onStop = { onAction(ApiBasedBluetoothCommunicationAction.StopProcessDataStream) }
					)
				}
			}

			item {
				RpcLogs(state.rpcTestState.logs) {
					onAction(ApiBasedBluetoothCommunicationAction.ClickClearLogs)
				}
			}

			item {
				HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
				Text(
					text = "Devices",
					fontWeight = FontWeight.Bold,
					fontSize = 22.sp,
				)
			}

			item { Text("Paired Devices", fontWeight = FontWeight.SemiBold) }
			if (state.pairedDevices.isEmpty()) {
				item { Text("No paired devices", color = Color.Gray) }
			} else {
				items(state.pairedDevices) { device ->
					BluetoothDeviceItem(
						name = device.name,
						address = device.address.value,
						connectionState = device.connectionState,
						pairingState = device.pairingState,
						onClick = { onAction(ApiBasedBluetoothCommunicationAction.ClickPairedDevice(device)) },
						onLongClick = { onAction(ApiBasedBluetoothCommunicationAction.LongClickPairedDevice(device)) },
						onPair = { onAction(ApiBasedBluetoothCommunicationAction.PairDevice(device)) },
						onUnpair = { onAction(ApiBasedBluetoothCommunicationAction.UnpairDevice(device)) },
						modifier = Modifier.fillMaxWidth()
					)
				}
			}

			item { Spacer(Modifier.height(8.dp)) }
			item { Text("Scanned Devices", fontWeight = FontWeight.SemiBold) }
			if (state.scannedDevices.isEmpty()) {
				item { Text("No scanned devices", color = Color.Gray) }
			} else {
				items(state.scannedDevices) { scannedDevice ->
					BluetoothDeviceItem(
						name = scannedDevice.device.name,
						address = scannedDevice.device.address.value,
						connectionState = scannedDevice.device.connectionState,
						pairingState = scannedDevice.device.pairingState,
						rssi = scannedDevice.rssi,
						onClick = { onAction(ApiBasedBluetoothCommunicationAction.ClickScannedDevice(scannedDevice)) },
						onLongClick = { onAction(ApiBasedBluetoothCommunicationAction.LongClickScannedDevice(scannedDevice)) },
						onPair = { onAction(ApiBasedBluetoothCommunicationAction.PairDevice(scannedDevice.device)) },
						onUnpair = { onAction(ApiBasedBluetoothCommunicationAction.UnpairDevice(scannedDevice.device)) },
						modifier = Modifier.fillMaxWidth()
					)
				}
			}
		}
	}
}

@Composable
private fun RpcCategory(title: String, content: @Composable ColumnScope.() -> Unit) {
	Card(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 4.dp),
		colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
	) {
		Column(modifier = Modifier.padding(12.dp)) {
			Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
			Spacer(Modifier.height(8.dp))
			content()
		}
	}
}

@Composable
private fun VibrationButton(onAction: (ApiBasedBluetoothCommunicationAction) -> Unit) {
	Button(
		onClick = {},
		modifier = Modifier.pointerInput(Unit) {
			awaitEachGesture {
				awaitFirstDown(requireUnconsumed = false)
				onAction(ApiBasedBluetoothCommunicationAction.StartRemoteVibration)
				waitForUpOrCancellation()
				onAction(ApiBasedBluetoothCommunicationAction.StopRemoteVibration)
			}
		}
	) {
		Text("Hold")
	}
}

@Composable
private fun FlowControl(label: String, isActive: Boolean, value: String?, onStart: () -> Unit, onStop: () -> Unit) {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Column(modifier = Modifier.weight(1f)) {
			Text(label)
			if (value != null) {
				Text("Latest: $value", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
			}
		}
		if (isActive) {
			Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
				Text("Stop")
			}
		} else {
			Button(onClick = onStart) {
				Text("Start")
			}
		}
	}
}

@Composable
fun RpcLogs(logs: List<String>, onClear: () -> Unit) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(8.dp))
			.background(Color.Black.copy(alpha = 0.05f))
			.padding(8.dp)
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically
		) {
			Text("RPC Logs", fontWeight = FontWeight.SemiBold)
			Button(
				onClick = onClear,
				colors = ButtonDefaults.textButtonColors(),
				contentPadding = PaddingValues(0.dp)
			) {
				Text("Clear", fontSize = 12.sp)
			}
		}
		val logState = rememberLazyListState()
		LaunchedEffect(logs.size) {
			if (logs.isNotEmpty()) {
				logState.animateScrollToItem(logs.size - 1)
			}
		}
		LazyColumn(
			state = logState,
			modifier = Modifier
				.height(150.dp)
				.fillMaxWidth()
		) {
			items(logs) { log ->
				Text(
					text = log,
					fontSize = 12.sp,
					fontFamily = FontFamily.Monospace,
					modifier = Modifier.padding(vertical = 1.dp)
				)
			}
		}
	}
}

@Preview(showBackground = true)
@Composable
private fun Preview() = BasePreview {
	ApiBasedBluetoothCommunicationScreen(
		state = ApiBasedBluetoothCommunicationState(
			isBluetoothSupported = true,
			isBluetoothOn = true,
			useSecureConnection = false,
			currentDeviceAddress = "00:11:22:33:44:55",
			bluetoothDeviceName = "Test Device",
			rpcTestState = ApiBasedBluetoothCommunicationState.RpcTestState(
				logs = listOf("Connected", "Started lightSensor", "Received value: 100.0")
			)
		),
		onAction = {}
	)
}
