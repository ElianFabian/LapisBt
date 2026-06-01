package com.elianfabian.lapisbt.feature.manual_bluetooth_communication.presentation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elianfabian.lapisbt.app.common.presentation.component.BluetoothControlPanel
import com.elianfabian.lapisbt.app.common.presentation.component.BluetoothDeviceItem
import com.elianfabian.lapisbt.app.common.presentation.component.DeviceSelection
import com.elianfabian.lapisbt.app.common.presentation.component.DeviceSelector
import com.elianfabian.lapisbt.app.common.presentation.component.EnableBluetoothPlaceholder
import com.elianfabian.lapisbt.app.common.presentation.component.PermissionDialog
import com.elianfabian.lapisbt.app.common.presentation.model.BluetoothMessage
import com.elianfabian.lapisbt.app.common.util.simplestack.compose.BasePreview
import com.elianfabian.lapisbt.model.BluetoothDevice

@Composable
fun ManualBluetoothCommunicationScreen(
	state: ManualBluetoothCommunicationState,
	onAction: (action: ManualBluetoothCommunicationAction) -> Unit,
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
			Box(
				modifier = Modifier
					.fillMaxSize()
					.weight(1F)
			) {
				if (!state.isBluetoothOn) {
					EnableBluetoothPlaceholder(
						onEnableClick = { onAction(ManualBluetoothCommunicationAction.EnableBluetooth) }
					)
				} else {
					BluetoothDeviceList(
						state = state,
						onAction = onAction,
						modifier = Modifier
							.fillMaxSize()
							.padding(horizontal = 16.dp)
					)
				}
			}

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
					onSelectDevice = { onAction(ManualBluetoothCommunicationAction.SelectTargetDeviceToMessage(it)) },
					onSelectAll = { onAction(ManualBluetoothCommunicationAction.SelectAllDevicesToMessage) },
					modifier = Modifier.padding(bottom = 8.dp)
				)

				Row(
					verticalAlignment = Alignment.Top,
					modifier = Modifier.fillMaxWidth()
				) {
					TextField(
						value = state.enteredMessage,
						onValueChange = { onAction(ManualBluetoothCommunicationAction.EnterMessage(it)) },
						placeholder = { Text(if (state.isBluetoothOn) "Message to send" else "Bluetooth is off") },
						enabled = state.isBluetoothOn,
						modifier = Modifier.weight(1f)
					)
					IconButton(
						onClick = { onAction(ManualBluetoothCommunicationAction.SendMessage) },
						enabled = state.isBluetoothOn,
						modifier = Modifier.size(56.dp)
					) {
						Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
					}
				}

				BluetoothControlPanel(
					isScanning = state.isScanning,
					isWaitingForConnection = state.isWaitingForConnection,
					useSecureConnection = state.useSecureConnection,
					onToggleScan = {
						if (state.isScanning) onAction(ManualBluetoothCommunicationAction.StopScan)
						else onAction(ManualBluetoothCommunicationAction.StartScan)
					},
					onToggleServer = {
						if (state.isWaitingForConnection) onAction(ManualBluetoothCommunicationAction.StopServer)
						else onAction(ManualBluetoothCommunicationAction.StartServer)
					},
					onCheckSecureConnection = { onAction(ManualBluetoothCommunicationAction.CheckUseSecureConnection(it)) },
					modifier = Modifier.padding(WindowInsets.navigationBars.asPaddingValues())
				)
			}
		}
	}
}

@Composable
private fun BluetoothDeviceList(
	state: ManualBluetoothCommunicationState,
	onAction: (action: ManualBluetoothCommunicationAction) -> Unit,
	modifier: Modifier = Modifier,
) {
	val lazyListState = rememberLazyListState()

	LaunchedEffect(state.messages) {
		if (lazyListState.layoutInfo.totalItemsCount > 0) {
			lazyListState.animateScrollToItem(lazyListState.layoutInfo.totalItemsCount - 1)
		}
	}

	val haptics = LocalHapticFeedback.current

	LazyColumn(
		state = lazyListState,
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
								onValueChange = { onAction(ManualBluetoothCommunicationAction.EnterBluetoothDeviceName(it)) },
								modifier = Modifier.weight(1F)
							)
							Spacer(Modifier.width(6.dp))
							IconButton(
								onClick = {
									onAction(ManualBluetoothCommunicationAction.SaveBluetoothDeviceName)
									haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
								},
							) {
								Icon(Icons.Filled.CheckCircle, contentDescription = null)
							}
						}
					} else {
						Row(
							verticalAlignment = Alignment.CenterVertically,
						) {
							Text(
								text = "Your device name: '${state.bluetoothDeviceName}'",
								fontSize = 18.sp,
								modifier = Modifier.weight(1F)
							)
							if (state.isBluetoothOn) {
								IconButton(
									onClick = {
										onAction(ManualBluetoothCommunicationAction.EditBluetoothDeviceName)
										haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
									},
								) {
									Icon(Icons.Default.Edit, contentDescription = null)
								}
							}
						}
					}
				}
				Text(
					text = "Your device address: '${state.currentDeviceAddress ?: "unknown"}'",
					fontSize = 18.sp,
				)
				Spacer(Modifier.height(8.dp))
				Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Button(onClick = { onAction(ManualBluetoothCommunicationAction.MakeDeviceDiscoverable) }) {
						Text("Discoverable")
					}
					Button(onClick = { onAction(ManualBluetoothCommunicationAction.OpenBluetoothSettings) }) {
						Text("BT Settings")
					}
				}
			}
		}

		item {
			HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
			Text(
				text = "Devices",
				fontWeight = FontWeight.Bold,
				fontSize = 24.sp,
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
					onClick = { onAction(ManualBluetoothCommunicationAction.ClickPairedDevice(device)) },
					onLongClick = { onAction(ManualBluetoothCommunicationAction.LongClickPairedDevice(device)) },
					onPair = { onAction(ManualBluetoothCommunicationAction.PairDevice(device)) },
					onUnpair = { onAction(ManualBluetoothCommunicationAction.UnpairDevice(device)) },
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
					onClick = { onAction(ManualBluetoothCommunicationAction.ClickScannedDevice(scannedDevice)) },
					onLongClick = { onAction(ManualBluetoothCommunicationAction.LongClickScannedDevice(scannedDevice)) },
					onPair = { onAction(ManualBluetoothCommunicationAction.PairDevice(scannedDevice.device)) },
					onUnpair = { onAction(ManualBluetoothCommunicationAction.UnpairDevice(scannedDevice.device)) },
					modifier = Modifier.fillMaxWidth()
				)
			}
		}

		item {
			HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
			Text(
				text = "Messages",
				fontWeight = FontWeight.Bold,
				fontSize = 24.sp,
			)
		}
		if (state.messages.isEmpty()) {
			item { Text("No messages", color = Color.Gray) }
		} else {
			items(state.messages) { message ->
				Message(
					senderName = message.senderName,
					isFromLocalUser = message.senderAddress == state.currentDeviceAddress,
					content = message.content,
					senderAddress = message.senderAddress,
					onClick = { onAction(ManualBluetoothCommunicationAction.ClickMessage(message)) },
				)
			}
		}
	}
}

@Composable
private fun Message(
	senderName: String?,
	senderAddress: String,
	isFromLocalUser: Boolean,
	content: String,
	onClick: () -> Unit,
	modifier: Modifier = Modifier,
	isLoading: Boolean = false,
	progress: Float = 0F,
) {
	val backgroundColor = if (isFromLocalUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
	val textColor = if (isFromLocalUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

	Row(
		horizontalArrangement = if (isFromLocalUser) Arrangement.End else Arrangement.Start,
		modifier = modifier
			.fillMaxWidth()
			.clickable { onClick() }
	) {
		Column(
			modifier = Modifier
				.background(backgroundColor, shape = RoundedCornerShape(12.dp))
				.padding(12.dp)
				.widthIn(max = 280.dp)
		) {
			if (senderAddress.isNotBlank() && !isFromLocalUser) {
				Text(
					text = if (senderName != null) "$senderName · $senderAddress" else senderAddress,
					style = MaterialTheme.typography.labelMedium,
					color = textColor.copy(alpha = 0.7f),
					maxLines = 1,
					overflow = TextOverflow.Ellipsis
				)
				Spacer(modifier = Modifier.height(4.dp))
			}
			if (isLoading) {
				val animatedProgress by animateFloatAsState(
					targetValue = progress.coerceIn(0F, 1F),
					animationSpec = tween(durationMillis = 35, easing = FastOutSlowInEasing),
					label = "AnimatedProgress",
				)

				LinearProgressIndicator(
					progress = { animatedProgress },
					drawStopIndicator = {},
					modifier = Modifier.fillMaxWidth()
				)
			} else {
				Text(
					text = content,
					style = MaterialTheme.typography.bodyMedium,
					color = textColor
				)
			}
		}
	}
}

@Preview(showBackground = true)
@Composable
private fun Preview() = BasePreview {
	val devices = listOf("Device 1", "Device 2").map { name ->
		BluetoothDevice(
			name = name,
			alias = name,
			type = BluetoothDevice.Type.Unknown,
			deviceClass = BluetoothDevice.DeviceClass.Phone.Smart,
			majorDeviceClass = BluetoothDevice.MajorDeviceClass.Phone,
			uuids = emptyList(),
			addressType = BluetoothDevice.AddressType.Unknown,
			address = BluetoothDevice.Address("11:22:33:44:55:66"),
			connectionState = BluetoothDevice.ConnectionState.Disconnected,
			pairingState = BluetoothDevice.PairingState.Paired,
		)
	}

	ManualBluetoothCommunicationScreen(
		state = ManualBluetoothCommunicationState(
			bluetoothDeviceName = "Bluetooth Device",
			pairedDevices = devices,
			isBluetoothSupported = true,
			isScanning = true,
			isBluetoothOn = true,
			useSecureConnection = false,
			messages = listOf(
				BluetoothMessage(content = "Hello!", senderName = "User", senderAddress = "XX:XX", isRead = true),
				BluetoothMessage(content = "Hi there!", senderName = null, senderAddress = "YY:YY", isRead = true)
			),
			currentDeviceAddress = "XX:XX",
			connectedDevices = devices,
			deviceSelection = DeviceSelection.None,
		),
		onAction = {},
	)
}
