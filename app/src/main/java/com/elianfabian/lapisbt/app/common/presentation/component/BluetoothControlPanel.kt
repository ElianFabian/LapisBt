package com.elianfabian.lapisbt.app.common.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elianfabian.lapisbt.app.common.util.simplestack.compose.BasePreview

@Composable
fun BluetoothControlPanel(
	isScanning: Boolean,
	isWaitingForConnection: Boolean,
	useSecureConnection: Boolean,
	onToggleScan: () -> Unit,
	onToggleServer: () -> Unit,
	onCheckSecureConnection: (Boolean) -> Unit,
	modifier: Modifier = Modifier,
) {
	val haptics = LocalHapticFeedback.current

	Row(
		horizontalArrangement = Arrangement.SpaceAround,
		verticalAlignment = Alignment.CenterVertically,
		modifier = modifier.fillMaxWidth()
	) {
		Button(
			onClick = {
				onToggleScan()
				haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
			}
		) {
			AnimatedVisibility(isScanning) {
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
				text = if (isScanning) "Stop scan" else "Start scan",
				modifier = Modifier.animateContentSize()
			)
		}

		Row(verticalAlignment = Alignment.CenterVertically) {
			Button(
				onClick = {
					onToggleServer()
					haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
				},
			) {
				AnimatedVisibility(isWaitingForConnection) {
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
					text = if (isWaitingForConnection) "Stop server" else "Start server",
				)
			}
			Spacer(Modifier.width(4.dp))
			Row(
				verticalAlignment = Alignment.CenterVertically,
				modifier = Modifier
					.clip(RoundedCornerShape(4.dp))
					.clickable {
						val newValue = !useSecureConnection
						haptics.performHapticFeedback(if (newValue) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
						onCheckSecureConnection(newValue)
					}
					.padding(end = 4.dp)
			) {
				Checkbox(
					checked = useSecureConnection,
					onCheckedChange = null,
					modifier = Modifier.size(32.dp)
				)
				Text(
					text = "Secure\n(Pairing)",
					fontSize = 10.sp,
					lineHeight = 11.sp,
					textAlign = TextAlign.Center,
					color = MaterialTheme.colorScheme.onPrimaryContainer
				)
			}
		}
	}
}

@Preview(showBackground = true)
@Composable
private fun BluetoothControlPanelPreview() = BasePreview {
	var isScanning by remember { mutableStateOf(false) }
	var isWaiting by remember { mutableStateOf(false) }
	var isSecure by remember { mutableStateOf(false) }

	Column(
		verticalArrangement = Arrangement.spacedBy(16.dp),
		modifier = Modifier.padding(16.dp)
	) {
		Text("Idle State:")
		BluetoothControlPanel(
			isScanning = false,
			isWaitingForConnection = false,
			useSecureConnection = false,
			onToggleScan = {},
			onToggleServer = {},
			onCheckSecureConnection = {}
		)

		Text("Active States:")
		BluetoothControlPanel(
			isScanning = true,
			isWaitingForConnection = true,
			useSecureConnection = true,
			onToggleScan = {},
			onToggleServer = {},
			onCheckSecureConnection = {}
		)

		HorizontalDivider()

		Text("Interactive (Test toggles):")
		BluetoothControlPanel(
			isScanning = isScanning,
			isWaitingForConnection = isWaiting,
			useSecureConnection = isSecure,
			onToggleScan = { isScanning = !isScanning },
			onToggleServer = { isWaiting = !isWaiting },
			onCheckSecureConnection = { isSecure = it }
		)
	}
}
