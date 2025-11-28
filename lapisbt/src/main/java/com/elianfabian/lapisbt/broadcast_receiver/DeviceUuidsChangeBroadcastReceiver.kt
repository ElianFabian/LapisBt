package com.elianfabian.lapisbt.broadcast_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice
import java.util.UUID

class DeviceUuidsChangeBroadcastReceiver(
	private val onUuidsChange: (device: AndroidBluetoothDevice, uuids: List<UUID>) -> Unit,
) : BroadcastReceiver() {

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action == AndroidBluetoothDevice.ACTION_UUID) {
			val device = intent.getParcelableExtra<AndroidBluetoothDevice>(AndroidBluetoothDevice.EXTRA_DEVICE) ?: return
			val uuids = intent.extras?.getParcelableArray(AndroidBluetoothDevice.EXTRA_UUID).orEmpty().map { UUID.fromString(it.toString()) }

			onUuidsChange(device, uuids)
		}
	}
}
