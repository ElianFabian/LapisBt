package com.elianfabian.bluetooth_testing

import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.UUID

class MainTestingActivity : AppCompatActivity() {

	private val lapisBt by lazy {
		application.lapisBt
	}


	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)

		Log.i(TAG, "onNewIntent: ${intent.contentToString()}")

		when (intent.action) {
			"start-scan" -> {
				Log.i(TAG, "START start-scan")
				val result = lapisBt.startScan()
				Log.i(TAG, "END start-scan: $result")
			}
			"stop-scan" -> {
				Log.i(TAG, "START stop-scan")
				val result = lapisBt.stopScan()
				Log.i(TAG, "END stop-scan: $result")
			}
			"start-server" -> {
				lifecycleScope.launch {
					Log.i(TAG, "START start-server")

					val uuid = intent.getStringExtra("uuid") ?: return@launch

					val result = lapisBt.startBluetoothServer(
						serviceName = "Test",
						serviceUuid = UUID.fromString(uuid),
					)

					Log.i(TAG, "END start-server: $result")
				}
			}
			"start-serverWithoutPairing" -> {
				lifecycleScope.launch {
					Log.i(TAG, "START start-serverWithoutPairing")

					val uuid = intent.getStringExtra("uuid") ?: return@launch

					val result = lapisBt.startBluetoothServerWithoutPairing(
						serviceName = "Test",
						serviceUuid = UUID.fromString(uuid),
					)

					Log.i(TAG, "END start-serverWithoutPairing: $result")
				}
			}
			"stop-server" -> {
				Log.i(TAG, "START stop-server")
				val uuid = intent.getStringExtra("uuid") ?: return
				lapisBt.stopBluetoothServer(UUID.fromString(uuid))
				Log.i(TAG, "END stop-server")
			}
			"connectTo-device" -> {
				lifecycleScope.launch {
					Log.i(TAG, "START connectTo-device")

					val address = intent.getStringExtra("address") ?: return@launch
					val uuid = intent.getStringExtra("uuid") ?: return@launch

					val result = lapisBt.connectToDevice(
						deviceAddress = address,
						serviceUuid = UUID.fromString(uuid),
					)

					Log.i(TAG, "END connectTo-device: $result")
				}
			}
			"connectTo-deviceWithoutPairing" -> {
				lifecycleScope.launch {
					Log.i(TAG, "START connectTo-deviceWithoutPairing")

					val address = intent.getStringExtra("address") ?: return@launch
					val uuid = intent.getStringExtra("uuid") ?: return@launch

					val result = lapisBt.connectToDeviceWithoutPairing(
						deviceAddress = address,
						serviceUuid = UUID.fromString(uuid),
					)

					Log.i(TAG, "END connectTo-deviceWithoutPairing: $result")
				}
			}
		}
	}


	companion object {
		const val TAG = "MainTestingActivity"
	}
}
