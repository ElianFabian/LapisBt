package com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.elianfabian.lapisbt.app.common.util.simplestack.compose.BasePreview
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt.model.ScannedBluetoothDevice
import kotlin.random.Random

@Composable
fun ApiBasedBluetoothCommunicationScreen(
	state: ApiBasedBluetoothCommunicationState,
	onAction: (action: ApiBasedBluetoothCommunicationAction) -> Unit,
) {
	if (state.permissionDialog != null) {
		val dialogState = state.permissionDialog
		Dialog(
			properties = DialogProperties(
				dismissOnBackPress = true,
				usePlatformDefaultWidth = true,
			),
			onDismissRequest = {
				dialogState.onDismissRequest()
			}
		) {
			Card {
				Column(
					modifier = Modifier.padding(16.dp)
				) {
					Text(
						text = dialogState.title,
						fontSize = 18.sp,
						fontWeight = FontWeight.SemiBold,
					)
					Spacer(Modifier.height(8.dp))
					Text(
						text = dialogState.message,
					)
					Spacer(modifier = Modifier.height(16.dp))
					Button(
						onClick = {
							dialogState.onAction()
						},
						modifier = Modifier
							.fillMaxWidth()
					) {
						Text(dialogState.actionName)
					}
				}
			}
		}
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
	}
	else {
		val haptics = LocalHapticFeedback.current
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(
					WindowInsets.statusBars
						.asPaddingValues()
				)
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
				var isDeviceSelectorExpanded by remember {
					mutableStateOf(false)
				}

				if (state.connectedDevices.isEmpty()) {
					Text(text = "No connected devices", modifier = Modifier.padding(8.dp))
				}
				else {
					Card(
						onClick = {
							isDeviceSelectorExpanded = true
						},
					) {
						Column(
							verticalArrangement = Arrangement.Center,
							modifier = Modifier
								.fillMaxWidth()
								.padding(8.dp)
						) {
							when (state.selectedDevice) {
								is ApiBasedBluetoothCommunicationState.SelectedDevice.AllDevices -> {
									Text(
										text = "All devices (not recommended for RPC)",
										fontWeight = FontWeight.Bold,
									)
								}
								is ApiBasedBluetoothCommunicationState.SelectedDevice.Device -> {
									val device = state.selectedDevice.device

									Text(text = device.name ?: "(No name)")
									Spacer(Modifier.height(4.dp))
									Text(text = device.address.value, fontSize = 12.sp)
								}

								is ApiBasedBluetoothCommunicationState.SelectedDevice.None -> {
									Text(text = "No selected device (Select for RPC)")
								}
							}
						}
					}
				}
				DropdownMenu(
					expanded = isDeviceSelectorExpanded,
					onDismissRequest = {
						isDeviceSelectorExpanded = false
					},
					modifier = Modifier.fillMaxWidth()
				) {
					state.connectedDevices.forEach { device ->
						DropdownMenuItem(
							text = {
								Text(text = device.name ?: device.address.value)
							},
							onClick = {
								onAction(ApiBasedBluetoothCommunicationAction.SelectTargetDeviceToMessage(device))
								isDeviceSelectorExpanded = false
							},
						)
					}
				}

				Spacer(Modifier.height(8.dp))
				Row(
					horizontalArrangement = Arrangement.SpaceAround,
					modifier = Modifier
						.fillMaxWidth()
						.padding(
							WindowInsets.navigationBars
								.asPaddingValues()
						)
				) {
					Button(
						onClick = {
							if (state.isScanning) {
								onAction(ApiBasedBluetoothCommunicationAction.StopScan)
							}
							else {
								onAction(ApiBasedBluetoothCommunicationAction.StartScan)
							}
							haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
						}
					) {
						AnimatedVisibility(state.isScanning) {
							Row {
								CircularProgressIndicator(
									color = MaterialTheme.colorScheme.onPrimary,
									strokeWidth = 3.dp,
									modifier = Modifier.size(20.dp)
								)
								Spacer(Modifier.width(8.dp))
							}
						}
						Text(
							text = if (state.isScanning) {
								"Stop scan"
							}
							else "Start scan",
							modifier = Modifier.animateContentSize()
						)
					}
					Row(verticalAlignment = Alignment.CenterVertically) {
						Button(
							onClick = {
								if (state.isWaitingForConnection) {
									onAction(ApiBasedBluetoothCommunicationAction.StopServer)
								}
								else {
									onAction(ApiBasedBluetoothCommunicationAction.StartServer)
								}
								haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
							},
						) {
							AnimatedVisibility(state.isWaitingForConnection) {
								Row {
									CircularProgressIndicator(
										color = MaterialTheme.colorScheme.onPrimary,
										strokeWidth = 3.dp,
										modifier = Modifier.size(20.dp)
									)
									Spacer(Modifier.width(8.dp))
								}
							}
							Text(
								text = if (state.isWaitingForConnection) {
									"Stop server"
								}
								else "Start server",
							)
						}
						Checkbox(
							checked = state.useSecureConnection,
							onCheckedChange = { checked ->
								haptics.performHapticFeedback(
									if (checked) {
										HapticFeedbackType.ToggleOn
									}
									else HapticFeedbackType.ToggleOff
								)
								onAction(ApiBasedBluetoothCommunicationAction.CheckUseSecureConnection(checked))
							},
						)
					}
				}
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
					}
					else {
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
					modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
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
						Button(
							onClick = {
								println("$$$ Button Clicked (Normal)")
							},
							modifier = Modifier.pointerInput(Unit) {
								awaitEachGesture {
									awaitFirstDown(requireUnconsumed = false)
									println("$$$ Vibrate Press")
									onAction(ApiBasedBluetoothCommunicationAction.StartRemoteVibration)

									waitForUpOrCancellation()
									println("$$$ Vibrate Release")
									onAction(ApiBasedBluetoothCommunicationAction.StopRemoteVibration)
								}
							}
						) {
							Text("Hold")
						}
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
							modifier = Modifier.align(Alignment.CenterEnd).padding(top = 4.dp)
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
							onClick = { onAction(ApiBasedBluetoothCommunicationAction.ClickClearLogs) },
							colors = ButtonDefaults.textButtonColors(),
							contentPadding = PaddingValues(0.dp)
						) {
							Text("Clear", fontSize = 12.sp)
						}
					}
					val logState = rememberLazyListState()
					LaunchedEffect(state.rpcTestState.logs.size) {
						if (state.rpcTestState.logs.isNotEmpty()) {
							logState.animateScrollToItem(state.rpcTestState.logs.size - 1)
						}
					}
					LazyColumn(
						state = logState,
						modifier = Modifier.height(150.dp).fillMaxWidth()
					) {
						items(state.rpcTestState.logs) { log ->
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
		modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
private fun BluetoothDeviceItem(
	name: String?,
	address: String,
	connectionState: BluetoothDevice.ConnectionState,
	pairingState: BluetoothDevice.PairingState,
	rssi: Short? = null,
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	onPair: () -> Unit,
	onUnpair: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.clip(RoundedCornerShape(8.dp))
			.background(
				when (connectionState) {
					BluetoothDevice.ConnectionState.Connected -> Color(0xFFA5D6A7)
					BluetoothDevice.ConnectionState.Connecting -> Color(0xFFFFF59D)
					BluetoothDevice.ConnectionState.Disconnected -> MaterialTheme.colorScheme.surfaceVariant
				}
			)
			.padding(12.dp)
			.combinedClickable(onClick = onClick, onLongClick = onLongClick)
	) {
		Column {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Column(modifier = Modifier.weight(1f)) {
					Text(text = name ?: "(No name)", fontSize = 16.sp, fontWeight = FontWeight.Medium)
					Text(text = address, fontSize = 12.sp)
				}
				if (rssi != null) {
					Text(text = "$rssi dBm", fontSize = 12.sp)
				}
			}
			Spacer(Modifier.height(4.dp))
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				when (pairingState) {
					BluetoothDevice.PairingState.None -> Button(onClick = onPair, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)) { Text("Pair", fontSize = 12.sp) }
					BluetoothDevice.PairingState.Pairing -> Text("Pairing...", fontSize = 12.sp)
					BluetoothDevice.PairingState.Paired -> Button(onClick = onUnpair, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)) { Text("Unpair", fontSize = 12.sp) }
				}
			}
		}
	}
}

// Minimal ColumnScope for RpcCategory
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
