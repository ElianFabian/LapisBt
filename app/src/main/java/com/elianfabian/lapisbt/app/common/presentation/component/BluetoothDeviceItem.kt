package com.elianfabian.lapisbt.app.common.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elianfabian.lapisbt.app.common.util.simplestack.compose.BasePreview
import com.elianfabian.lapisbt.model.BluetoothDevice

@Composable
fun BluetoothDeviceItem(
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
		if (pairingState == BluetoothDevice.PairingState.Pairing) {
			CircularProgressIndicator(
				strokeWidth = 3.dp,
				modifier = Modifier
					.size(20.dp)
					.align(Alignment.CenterVertically)
			)
			Spacer(Modifier.padding(horizontal = 6.dp))
		}
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
					BluetoothDevice.PairingState.None -> Button(
						onClick = onPair,
						contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
						modifier = Modifier.height(32.dp)
					) {
						Text("Pair", fontSize = 12.sp)
					}
					BluetoothDevice.PairingState.Pairing -> Text("Pairing...", fontSize = 12.sp)
					BluetoothDevice.PairingState.Paired -> Button(
						onClick = onUnpair,
						contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
						modifier = Modifier.height(32.dp)
					) {
						Text("Unpair", fontSize = 12.sp)
					}
				}
			}
		}
	}
}

@Preview(showBackground = true)
@Composable
private fun BluetoothDeviceItemPreview() = BasePreview {
	Column(
		verticalArrangement = Arrangement.spacedBy(8.dp),
		modifier = Modifier.padding(16.dp)
	) {
		BluetoothDeviceItem(
			name = "Connected Device",
			address = "00:11:22:33:44:55",
			connectionState = BluetoothDevice.ConnectionState.Connected,
			pairingState = BluetoothDevice.PairingState.Paired,
			onClick = {},
			onLongClick = {},
			onPair = {},
			onUnpair = {}
		)
		BluetoothDeviceItem(
			name = "Connecting Device",
			address = "AA:BB:CC:DD:EE:FF",
			connectionState = BluetoothDevice.ConnectionState.Connecting,
			pairingState = BluetoothDevice.PairingState.Pairing,
			onClick = {},
			onLongClick = {},
			onPair = {},
			onUnpair = {}
		)
		BluetoothDeviceItem(
			name = "Scanned Device",
			address = "11:22:33:44:55:66",
			connectionState = BluetoothDevice.ConnectionState.Disconnected,
			pairingState = BluetoothDevice.PairingState.None,
			rssi = -65,
			onClick = {},
			onLongClick = {},
			onPair = {},
			onUnpair = {}
		)
	}
}
