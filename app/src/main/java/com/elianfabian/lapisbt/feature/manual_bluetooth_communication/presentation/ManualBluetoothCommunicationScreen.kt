package com.elianfabian.lapisbt.feature.manual_bluetooth_communication.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.elianfabian.lapisbt.app.common.util.simplestack.compose.BasePreview
import com.elianfabian.lapisbt.model.BluetoothDevice
import kotlin.random.Random

@Composable
fun ManualBluetoothCommunicationScreen(
	state: ManualBluetoothCommunicationState,
	onAction: (action: ManualBluetoothCommunicationAction) -> Unit,
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
					Text(text = "No connected devices")
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
								is ManualBluetoothCommunicationState.SelectedDevice.AllDevices -> {
									Text(
										text = "All devices",
										fontWeight = FontWeight.Bold,
									)
								}
								is ManualBluetoothCommunicationState.SelectedDevice.Device -> {
									val device = state.selectedDevice.device

									Text(text = device.name ?: "(No name)")
									Spacer(Modifier.height(4.dp))
									Text(text = device.address)
								}

								is ManualBluetoothCommunicationState.SelectedDevice.None -> {
									Text(text = "No selected device")
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
								Text(text = device.name ?: device.address)
							},
							onClick = {
								onAction(ManualBluetoothCommunicationAction.SelectTargetDeviceToMessage(device))
								isDeviceSelectorExpanded = false
							},
						)
					}
					if (state.connectedDevices.isNotEmpty()) {
						DropdownMenuItem(
							text = {
								Text(
									text = "All devices",
									fontWeight = FontWeight.Bold,
									fontSize = 16.sp,
								)
							},
							onClick = {
								onAction(ManualBluetoothCommunicationAction.SelectAllDevicesToMessage)
								isDeviceSelectorExpanded = false
							},
						)
					}
				}

				Spacer(Modifier.height(8.dp))
				Row(
					verticalAlignment = Alignment.Top,
					modifier = Modifier.fillMaxWidth()
				) {
					TextField(
						value = state.enteredMessage,
						onValueChange = { value ->
							onAction(ManualBluetoothCommunicationAction.EnterMessage(value))
						},
						placeholder = {
							Text(
								text = if (state.isBluetoothOn) {
									"Message to send"
								}
								else "Bluetooth is off"
							)
						},
						enabled = state.isBluetoothOn,
						modifier = Modifier
							.weight(1f)
					)
					IconButton(
						onClick = {
							onAction(ManualBluetoothCommunicationAction.SendMessage)
							haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
						},
						enabled = state.isBluetoothOn,
						modifier = Modifier
							.size(56.dp)
					) {
						Icon(
							imageVector = Icons.AutoMirrored.Filled.Send,
							contentDescription = null,
						)
					}
				}
				Spacer(Modifier.height(6.dp))
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
								onAction(ManualBluetoothCommunicationAction.StopScan)
							}
							else {
								onAction(ManualBluetoothCommunicationAction.StartScan)
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
					Row {
						Button(
							onClick = {
								if (state.isWaitingForConnection) {
									onAction(ManualBluetoothCommunicationAction.StopServer)
								}
								else {
									onAction(ManualBluetoothCommunicationAction.StartServer)
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
								onAction(ManualBluetoothCommunicationAction.CheckUseSecureConnection(checked))
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
	state: ManualBluetoothCommunicationState,
	onAction: (action: ManualBluetoothCommunicationAction) -> Unit,
	modifier: Modifier = Modifier,
) {
	val lazyListState = rememberLazyListState()

	LaunchedEffect(state.messages) {
		if (lazyListState.layoutInfo.totalItemsCount == 0) {
			return@LaunchedEffect
		}
		lazyListState.scrollToItem(lazyListState.layoutInfo.totalItemsCount)
	}

	val haptics = LocalHapticFeedback.current

	LazyColumn(
		state = lazyListState,
		verticalArrangement = Arrangement.spacedBy(3.dp),
		contentPadding = PaddingValues(bottom = 15.dp),
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
									onAction(ManualBluetoothCommunicationAction.EnterBluetoothDeviceName(value))
								},
								modifier = Modifier.weight(1F)
							)
							Spacer(Modifier.width(6.dp))
							IconButton(
								onClick = {
									onAction(ManualBluetoothCommunicationAction.SaveBluetoothDeviceName)
									haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
								},
							) {
								Icon(
									imageVector = Icons.Filled.CheckCircle,
									contentDescription = null,
								)
							}
						}
						Spacer(Modifier.height(4.dp))
						Text(
							text = "Go to settings to check the name was effectively changed, in some devices this doesn't work, so you'll have to change it in bluetooth settings.",
							fontSize = 13.sp,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							lineHeight = 16.sp,
						)
					}
					else {
						Row(
							verticalAlignment = Alignment.CenterVertically,
						) {
							Text(
								text = "Your device name: '${state.bluetoothDeviceName}'",
								fontSize = 18.sp,
								modifier = Modifier.weight(1F)
							)
							if (state.isBluetoothOn) {
								Spacer(Modifier.width(4.dp))
								IconButton(
									onClick = {
										onAction(ManualBluetoothCommunicationAction.EditBluetoothDeviceName)
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
					fontSize = 18.sp,
				)
				Spacer(Modifier.height(8.dp))
				Button(
					onClick = {
						onAction(ManualBluetoothCommunicationAction.MakeDeviceDiscoverable)
						haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
					}
				) {
					Text("Make discoverable")
				}
				Spacer(modifier = Modifier.height(6.dp))
				Button(
					onClick = {
						onAction(ManualBluetoothCommunicationAction.OpenBluetoothSettings)
						haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
					}
				) {
					Text("Bluetooth settings")
				}
				Spacer(modifier = Modifier.height(6.dp))
				Button(
					onClick = {
						onAction(ManualBluetoothCommunicationAction.OpenDeviceInfoSettings)
						haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
					}
				) {
					Text("Device info settings")
				}
			}
			Spacer(modifier = Modifier.height(16.dp))
		}
		if (!state.isBluetoothOn) {
			item {
				Row(
					horizontalArrangement = Arrangement.Center,
					verticalAlignment = Alignment.CenterVertically,
					modifier = modifier.fillParentMaxSize(fraction = 0.6F)
				) {
					Button(
						onClick = {
							onAction(ManualBluetoothCommunicationAction.EnableBluetooth)
							haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
						},
					) {
						val icon = Icons.Filled.Bluetooth
						Icon(
							imageVector = icon,
							contentDescription = null,
							modifier = Modifier
								.offset(x = -icon.defaultWidth / 2)
						)
						Text(
							text = "Enable Bluetooth",
						)
					}
				}
			}
		}
		else {
			item {
				Text(
					text = "Paired devices",
					fontWeight = FontWeight.Bold,
					fontSize = 24.sp,
				)
			}
			if (state.pairedDevices.isEmpty()) {
				item {
					Text(
						text = "No paired devices",
						modifier = Modifier.padding(bottom = 8.dp)
					)
				}
			}
			else {
				items(state.pairedDevices) { device ->
					BluetoothDeviceItem(
						name = device.name,
						address = device.address,
						connectionState = device.connectionState,
						pairingState = device.pairingState,
						onClick = {
							onAction(ManualBluetoothCommunicationAction.ClickPairedDevice(device))
						},
						onLongClick = {
							onAction(ManualBluetoothCommunicationAction.LongClickPairedDevice(device))
						},
						onPair = {
							onAction(ManualBluetoothCommunicationAction.PairDevice(device))
						},
						onUnpair = {
							onAction(ManualBluetoothCommunicationAction.UnpairDevice(device))
						},
						modifier = Modifier
							.fillMaxWidth()
							.padding(vertical = 6.dp)
					)
				}
			}
			item {
				Spacer(Modifier.height(16.dp))
			}
			item {
				Text(
					text = "Scanned devices",
					fontWeight = FontWeight.Bold,
					fontSize = 24.sp,
				)
			}
			if (state.scannedDevices.isEmpty()) {
				item {
					Text(
						text = "No scanned devices",
						modifier = Modifier.padding(bottom = 8.dp)
					)
				}
			}
			else {
				items(state.scannedDevices) { device ->
					BluetoothDeviceItem(
						name = device.name,
						address = device.address,
						connectionState = device.connectionState,
						pairingState = device.pairingState,
						onClick = {
							onAction(ManualBluetoothCommunicationAction.ClickScannedDevice(device))
						},
						onLongClick = {
							onAction(ManualBluetoothCommunicationAction.LongClickScannedDevice(device))
						},
						onPair = {
							onAction(ManualBluetoothCommunicationAction.PairDevice(device))
						},
						onUnpair = {
							onAction(ManualBluetoothCommunicationAction.UnpairDevice(device))
						},
						modifier = Modifier
							.fillMaxWidth()
							.padding(vertical = 6.dp)
					)
				}
			}
			item {
				Spacer(Modifier.height(16.dp))
				Text(
					text = "Messages",
					fontWeight = FontWeight.Bold,
					fontSize = 24.sp,
				)
			}
			if (state.messages.isEmpty()) {
				item {
					Text(
						text = "No messages",
					)
				}
			}
			else {
				items(state.messages) { message ->
					Message(
						senderName = message.senderName,
						isFromLocalUser = message.senderAddress == state.currentDeviceAddress,
						content = message.content,
						senderAddress = message.senderAddress,
						onClick = {
							onAction(ManualBluetoothCommunicationAction.ClickMessage(message))
						},
					)
				}
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
	onClick: () -> Unit,
	onLongClick: () -> Unit,
	onPair: () -> Unit,
	onUnpair: () -> Unit,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.clip(RoundedCornerShape(8.dp))
//			.background(
//				when (connectionState) {
//					BluetoothDevice.ConnectionState.Connected -> Color.Green
//					BluetoothDevice.ConnectionState.Connecting -> Color.Yellow
//					BluetoothDevice.ConnectionState.Disconnected -> Color.LightGray
//					BluetoothDevice.ConnectionState.Disconnecting -> Color.Red
//				}
//			)
			.background(
				when (connectionState) {
					BluetoothDevice.ConnectionState.Connected -> Color(0xFFA5D6A7)
					BluetoothDevice.ConnectionState.Connecting -> Color(0xFFFFF59D)
					BluetoothDevice.ConnectionState.Disconnected -> MaterialTheme.colorScheme.surfaceVariant
					BluetoothDevice.ConnectionState.Disconnecting -> Color(0xFFEF9A9A)
				}
			)
			.padding(12.dp)
			.combinedClickable(
				onClick = {
					onClick()
				},
				onLongClick = {
					onLongClick()
				},
			)
	) {
		if (pairingState == BluetoothDevice.PairingState.Pairing) {
			CircularProgressIndicator(
				strokeWidth = 3.dp,
				modifier = Modifier
					.size(20.dp)
			)
		}
		Column {
			if (name != null) {
				Text(
					text = name,
					fontSize = 18.sp,
					lineHeight = 30.sp,
				)
				Spacer(modifier = Modifier.height(4.dp))
			}
			Row {
				Text(
					text = address,
					fontSize = 18.sp,
					lineHeight = 30.sp,
				)
				Spacer(Modifier.width(8.dp))
				when (pairingState) {
					BluetoothDevice.PairingState.None -> {
						Button(
							onClick = {
								onPair()
							}
						) {
							Text("Pair")
						}
					}
					BluetoothDevice.PairingState.Pairing -> {
						Text("Pairing...")
					}
					BluetoothDevice.PairingState.Paired -> {
						Button(
							onClick = {
								onUnpair()
							}
						) {
							Text("Unpair")
						}
					}
				}
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
			.clickable {
				onClick()
			}
	) {
		Column(
			modifier = Modifier
				.background(backgroundColor, shape = RoundedCornerShape(12.dp))
				.padding(12.dp)
				.widthIn(max = 280.dp)
		) {
			if (senderAddress.isNotBlank() && !isFromLocalUser) {
				Text(
					text = if (senderName != null) {
						"$senderName · $senderAddress"
					}
					else senderAddress,
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
					animationSpec = tween(
						durationMillis = 35,
						easing = FastOutSlowInEasing
					),
					label = "AnimatedProgress",
				)

				LinearProgressIndicator(
					progress = { animatedProgress },
					drawStopIndicator = {},
					modifier = Modifier.fillMaxWidth()
				)
			}
			else {
				Text(
					text = content,
					style = MaterialTheme.typography.bodyMedium,
					color = textColor
				)
			}
		}
	}
}

@Preview(
	showBackground = true,
//	widthDp = 392,
//	heightDp = 785,
//	fontScale = 1.4F,
)
@Composable
private fun Preview() = BasePreview {

	val deviceNames = listOf(
		"Device 1",
		"Device 2",
		"Device 3",
	)

	val devices = deviceNames.map { name ->
		BluetoothDevice(
			name = name,
			alias = name,
			type = BluetoothDevice.Type.Unknown,
			majorDeviceClass = BluetoothDevice.MajorDeviceClass.Phone,
			uuids = emptyList(),
			addressType = BluetoothDevice.AddressType.Unknown,
			address = "123:45:67:89:AB:$name",
			connectionState = BluetoothDevice.ConnectionState.Disconnected,
			pairingState = when (Random.nextInt(0, 3)) {
				0 -> BluetoothDevice.PairingState.Paired
				1 -> BluetoothDevice.PairingState.Pairing
				else -> BluetoothDevice.PairingState.None
			},
		)
	}

	ManualBluetoothCommunicationScreen(
		state = ManualBluetoothCommunicationState(
			bluetoothDeviceName = "Bluetooth Device",
			pairedDevices = devices.filter { it.pairingState.isPaired },
			scannedDevices = devices.filter { !it.pairingState.isPaired },
			isBluetoothSupported = true,
			isScanning = true,
			isBluetoothOn = false,
			useSecureConnection = false,
//			permissionDialog = _root_ide_package_.com.elianfabian.lapisbt.feature.manual_bluetooth_communication.presentation.ManualBluetoothCommunicationState.PermissionDialogState(
//				title = "Permission Denied",
//				message = "Please, enable Bluetooth permissions in settings.",
//				actionName = "Settings",
//				onAction = {},
//				onDismissRequest = {},
//			),
			messages = listOf(
				"Hey",
				"Hello",
				"How are you?",
//				"Good",
//				"How about you?",
//				"Fine, thanks",
//				"See you later",
//				"Bye",
//				"Take care",
//				"See you soon",
//				"Have a nice day",
//				"Goodbye",
//				"See you next time",
			).mapIndexed { index, s ->
				BluetoothMessage(
					content = s,
					senderName = null,
					senderAddress = "XX:60:E2:XX:98:XX",
					isRead = true,
				)
			},
			currentDeviceAddress = "XX:60:E2:XX:98:XX",
			enteredBluetoothDeviceName = null,
			isWaitingForConnection = false,
			connectedDevices = emptyList(),
			selectedDevice = ManualBluetoothCommunicationState.SelectedDevice.None,
			permissionDialog = null,
			enteredMessage = "",
		),
		onAction = {},
	)
}
