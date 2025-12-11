package com.elianfabian.bluetooth_testing

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.UUID

class MainTestingActivity : AppCompatActivity() {

	private val lapisBt by lazy {
		application.lapisBt
	}

	private val enableBluetoothLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		if (result.resultCode != RESULT_OK) {
			showEnableBluetoothDialog()
		}
	}


	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

		if (!lapisBt.state.value.isOn && Build.VERSION.SDK_INT < 33) {
			showEnableBluetoothDialog()
		}

		lifecycleScope.launch {
			lapisBt.isScanning.collect { isScanning ->
				Log.i(TAG, "isScanning: $isScanning")
			}
		}
		lifecycleScope.launch {
			lapisBt.events.collect { event ->
				Log.i(TAG, "event: $event")
			}
		}
	}


	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)

		Log.i(TAG, "onNewIntent: ${intent.contentToString()}")

		Toast.makeText(this, "action: ${intent.action}, extras: ${intent.extras?.contentToString()}", Toast.LENGTH_SHORT).show()

		when (val action = intent.action) {
			"start-scan" -> {
				logAction(action) {
					lapisBt.startScan()
				}
			}
			"stop-scan" -> {
				logAction(action) {
					lapisBt.stopScan()
				}
			}
			"start-server" -> {
				lifecycleScope.launch {
					logAction(action) {
						val uuid = intent.getStringExtra("uuid") ?: return@launch

						lapisBt.startBluetoothServer(
							serviceName = "Test",
							serviceUuid = UUID.fromString(uuid),
						)
					}
				}
			}
			"start-serverWithoutPairing" -> {
				lifecycleScope.launch {
					logAction(action) {
						val uuid = intent.getStringExtra("uuid") ?: return@launch

						lapisBt.startBluetoothServerWithoutPairing(
							serviceName = "Test",
							serviceUuid = UUID.fromString(uuid),
						)
					}
				}
			}
			"stop-server" -> {
				logAction(action) {
					val uuid = intent.getStringExtra("uuid") ?: return
					lapisBt.stopBluetoothServer(UUID.fromString(uuid))
				}
			}
			"connectTo-device" -> {
				lifecycleScope.launch {
					logAction(action) {
						val address = intent.getStringExtra("address") ?: return@launch
						val uuid = intent.getStringExtra("uuid") ?: return@launch

						lapisBt.connectToDevice(
							deviceAddress = address,
							serviceUuid = UUID.fromString(uuid),
						)
					}
				}
			}
			"connectTo-deviceWithoutPairing" -> {
				lifecycleScope.launch {
					logAction(action) {
						val address = intent.getStringExtra("address") ?: return@launch
						val uuid = intent.getStringExtra("uuid") ?: return@launch

						lapisBt.connectToDeviceWithoutPairing(
							deviceAddress = address,
							serviceUuid = UUID.fromString(uuid),
						)
					}
				}
			}
			"disconnectFrom-device" -> {
				lifecycleScope.launch {
					logAction(action) {
						val address = intent.getStringExtra("address") ?: return@launch

						lapisBt.disconnectFromDevice(
							deviceAddress = address,
						)
					}
				}
			}
			"cancel-connectionAttempt" -> {
				lifecycleScope.launch {
					logAction(action) {
						val address = intent.getStringExtra("address") ?: return@launch

						lapisBt.cancelConnectionAttempt(
							deviceAddress = address,
						)
					}
				}
			}
			"pair-device" -> {
				logAction(action) {
					val address = intent.getStringExtra("address") ?: return

					lapisBt.pairDevice(address)
				}
			}
			"unpair-device" -> {
				logAction(action) {
					val address = intent.getStringExtra("address") ?: return

					lapisBt.unpairDevice(address)
				}
			}
			"set-bluetoothName" -> {
				logAction(action) {
					val name = intent.getStringExtra("name") ?: return

					lapisBt.setBluetoothDeviceName(name)
				}
			}
			"send-data" -> {
				lifecycleScope.launch {
					logAction(action) {
						val address = intent.getStringExtra("address") ?: return@launch
						val byteArray = intent.getIntArrayExtra("bytes")?.map { it.toByte() }?.toByteArray() ?: return@launch

						lapisBt.sendData(address) { stream ->
							stream.write(byteArray)
						}
					}
				}
			}
		}
	}


	private inline fun logAction(name: String, action: () -> Any?) {
		Log.i(TAG, "START $name")
		val result = action()
		Log.i(TAG, "END $name: $result")
	}

	private fun showEnableBluetoothDialog() {
		enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
	}


	companion object {
		const val TAG = "MainTestingActivity"
	}
}
