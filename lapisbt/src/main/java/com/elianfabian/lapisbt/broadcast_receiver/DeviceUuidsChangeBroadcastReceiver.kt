package com.elianfabian.lapisbt.broadcast_receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.ParcelUuid
import com.elianfabian.lapisbt.util.AndroidBluetoothDevice
import java.util.UUID

internal class DeviceUuidsChangeBroadcastReceiver(
	// Passing null explicitly signals a timeout state to the handler
	private val onUuidsChange: (device: AndroidBluetoothDevice, uuids: List<UUID>?, isTimeout: Boolean) -> Unit,
) : BroadcastReceiver() {

	@Suppress("DEPRECATION")
	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action == AndroidBluetoothDevice.ACTION_UUID) {
			val device = intent.getParcelableExtra<AndroidBluetoothDevice>(AndroidBluetoothDevice.EXTRA_DEVICE) ?: return

			// Check if the extra itself is missing or null, which indicates a timeout
			val parcelableUuids = intent.getParcelableArrayExtra(AndroidBluetoothDevice.EXTRA_UUID)

			if (parcelableUuids == null) {
				onUuidsChange(device, null, true)
			} else {
				val uuids = parcelableUuids.map { (it as ParcelUuid).uuid }
				onUuidsChange(device, uuids, false)
			}
		}
	}
}
