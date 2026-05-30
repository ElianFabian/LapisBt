package com.elianfabian.lapisbt_rpc_testing

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.elianfabian.lapisbt.LapisBt
import com.elianfabian.lapisbt.model.BluetoothDevice
import com.elianfabian.lapisbt_rpc.LapisBtRpc
import com.elianfabian.lapisbt_rpc.getOrCreateBluetoothClientService
import com.elianfabian.lapisbt_rpc.registerBluetoothServerService
import com.elianfabian.lapisbt_rpc.unregisterBluetoothServerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

class MainRpcTestingActivity : AppCompatActivity(), LapisBtRpc.Registered {

	private val lapisBt by lazy { application.lapisBt }
	private val lapisBtRpc by lazy { application.lapisBtRpc }

	companion object {
		const val TAG = "MainRpcTestingActivity"
		val lastRpcResult = MutableStateFlow<String?>(null)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

		lifecycleScope.launch {
			lapisBt.events.collect { event ->
				Log.i(TAG, "Bluetooth event: $event")
				when (event) {
					is LapisBt.Event.OnDeviceConnected -> {
						Log.i(TAG, "Registering RPC services for ${event.device.address}")
						lapisBtRpc.registerBluetoothServerService<TestService>(
							deviceAddress = event.device.address,
							server = TestServiceServer()
						)
						lapisBtRpc.registerBluetoothServerService<SecondaryService>(
							deviceAddress = event.device.address,
							server = SecondaryServiceServer()
						)
					}
					is LapisBt.Event.OnDeviceDisconnected -> {
						Log.i(TAG, "Unregistering RPC services for ${event.device.address}")
						lapisBtRpc.unregisterBluetoothServerService<TestService>(event.device.address)
						lapisBtRpc.unregisterBluetoothServerService<SecondaryService>(event.device.address)
					}
					else -> {}
				}
			}
		}
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		Log.i(TAG, "onNewIntent: ${intent.action}, extras: ${intent.extras?.keySet()}")

		lifecycleScope.launch {
			try {
				handleIntent(intent)
			}
			catch (e: Exception) {
				Log.e(TAG, "Error handling intent", e)
				lastRpcResult.value = "Error: ${e.message}"
			}
		}
	}

	private suspend fun handleIntent(intent: Intent) {
		val action = intent.action ?: return
		val address = intent.getStringExtra("address")?.let { BluetoothDevice.Address(it) }
		val uuid = intent.getStringExtra("uuid")?.let { UUID.fromString(it) }

		when (action) {
			"start-server" -> {
				uuid?.let { lapisBt.startBluetoothServerWithoutPairing("RpcTest", it) }
			}
			"stop-server" -> {
				uuid?.let { lapisBt.stopBluetoothServer(it) }
			}
			"connect-device" -> {
				// TODO: add a retry mechanism since sometimes connecting may fail
				if (address != null && uuid != null) {
					lapisBt.connectToDeviceWithoutPairing(address, uuid)
				}
			}
			"disconnect-device" -> {
				address?.let { lapisBt.disconnectFromDevice(it) }
			}
			"call-greet" -> {
				address?.let {
					val service = lapisBtRpc.getOrCreateBluetoothClientService<TestService>(it)
					val name = intent.getStringExtra("name") ?: "World"
					val result = service.greet(name)
					logResult("call-greet", result)
				}
			}
			"call-add" -> {
				address?.let {
					val service = lapisBtRpc.getOrCreateBluetoothClientService<TestService>(it)
					val a = intent.getIntExtra("a", 0)
					val b = intent.getIntExtra("b", 0)
					val result = service.add(a, b)
					logResult("call-add", result.toString())
				}
			}
			"call-counter" -> {
				address?.let {
					val service = lapisBtRpc.getOrCreateBluetoothClientService<TestService>(it)
					val results = mutableListOf<Int>()
					println("$$$ START CALL-COUNTER")
					try {
						service.counter().collect { count ->
							results.add(count)
							Log.i(TAG, "Counter emission: $count")
						}
					}
					catch (e: CancellationException) {
						println("$$$$ call-counter: $e")
						throw e
					}
					println("$$$ END CALL-COUNTER")
					logResult("call-counter", results.toString())
				}
			}
			"call-get-request-info" -> {
				address?.let {
					val service = lapisBtRpc.getOrCreateBluetoothClientService<TestService>(it)
					val result = service.getRequestInfo()
					logResult("call-get-request-info", result)
				}
			}
			"call-ping" -> {
				address?.let {
					val service = lapisBtRpc.getOrCreateBluetoothClientService<SecondaryService>(it)
					val result = service.ping()
					logResult("call-ping", result)
				}
			}
			"call-process-flow" -> {
				address?.let { address ->
					val service = lapisBtRpc.getOrCreateBluetoothClientService<TestService>(address)
					val dataArray = intent.getIntArrayExtra("data") ?: intArrayOf()
					val dataFlow = flow {
						dataArray.forEach { emit(it) }
					}
					val result = service.processFlow(dataFlow)
					logResult("call-process-flow", result.toString())
				}
			}
		}
	}

	private fun logResult(action: String, result: String) {
		Log.i(TAG, "RPC Result ($action): $result")
		lastRpcResult.value = result
		runOnUiThread {
			Toast.makeText(this, "RPC $action: $result", Toast.LENGTH_SHORT).show()
		}
	}

	override fun onLapisServiceRegistered(deviceAddress: BluetoothDevice.Address) {
		Log.i(TAG, "onLapisServiceRegistered: $deviceAddress")
	}

	override fun onLapisServiceUnregistered(deviceAddress: BluetoothDevice.Address) {
		Log.i(TAG, "onLapisServiceUnregistered: $deviceAddress")
	}
}
