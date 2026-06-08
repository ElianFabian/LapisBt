package com.elianfabian.lapisbt.broadcast_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice

internal class DeviceFoundBroadcastReceiver(
	private val onDeviceFound: (device: AndroidBluetoothDevice, rssi: Short) -> Unit,
) : BroadcastReceiver() {

	// Extra values observed during testing:
	// - android.bluetooth.device.extra.DISCOVERY_RESULT_TYPE (e.g.: 1 or 2)
	// - android.bluetooth.device.extra.UUID (an array of uuids)
	// - android.bluetooth.device.extra.UUID_LE (an array of uuids)

	@Suppress("DEPRECATION")
	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action == AndroidBluetoothDevice.ACTION_FOUND) {
			val device = intent.getParcelableExtra<AndroidBluetoothDevice>(AndroidBluetoothDevice.EXTRA_DEVICE)
			val rssi = intent.getShortExtra(AndroidBluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
			if (device != null) {
				onDeviceFound(device, rssi)
			}
		}
	}
}
