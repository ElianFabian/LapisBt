package com.elianfabian.lapisbt.app.common.data

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import com.elianfabian.lapisbt.app.common.domain.AndroidHelper
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.elianfabian.lapisbt.app.common.util.simplestack.callbacks.ApplicationBackgroundStateChangeCallback
import com.elianfabian.lapisbt.app.common.util.simplestack.callbacks.OnCreateApplicationCallback
import com.zhuinden.simplestack.ScopedServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

class AndroidHelperImpl(
	private val context: Context,
	private val applicationScope: CoroutineScope,
	private val mainActivityHolder: MainActivityHolder,
) : AndroidHelper,
	ScopedServices.Registered,
	OnCreateApplicationCallback,
	ApplicationBackgroundStateChangeCallback {

	override fun onCreateApplication() {

	}

	override fun onServiceRegistered() {
		_isAppClosed = false
	}

	override fun onServiceUnregistered() {
		_isAppClosed = true
		//context.unregisterReceiver(_appCloseStateChangeReceiver)
	}

	private val activity: FragmentActivity get() = mainActivityHolder.mainActivity

	private var _isAppInBackground = false
	private var _isAppClosed = false


	override fun stopApplication() {
		Process.killProcess(Process.myPid())
	}

	override fun showToast(message: String) {
		mainActivityHolder.mainActivity.runOnUiThread {
			Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
		}
	}

	fun openNotificationSettings() {
		val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
				putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
			}
		} else {
			Intent("android.settings.APP_NOTIFICATION_SETTINGS").apply {
				putExtra("app_package", context.packageName)
				putExtra("app_uid", context.applicationInfo.uid)
			}
		}
		try {
			activity.startActivity(intent)
		} catch (e: Exception) {
			e.printStackTrace()
		}
	}

	override fun openAppSettings() {
		val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
			data = "package:${context.packageName}".toUri()
		}
		try {
			activity.startActivity(intent)
		} catch (e: Exception) {
			e.printStackTrace()
		}
	}

	override fun openBluetoothSettings() {
		val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
		try {
			activity.startActivity(intent)
		} catch (e: Exception) {
			e.printStackTrace()
		}
	}

	override fun openDeviceInfoSettings() {
		val intent = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
		try {
			activity.startActivity(intent)
		} catch (e: Exception) {
			e.printStackTrace()
		}
	}

	override suspend fun showMakeDeviceDiscoverableDialog(seconds: Int): Boolean = suspendCancellableCoroutine { continuation ->
		val launcher = createLauncher(
			contract = ActivityResultContracts.StartActivityForResult(),
			callback = { result ->
				continuation.resume(result.resultCode != Activity.RESULT_CANCELED)
			},
		)

		val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
			putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, seconds)
		}

		try {
			launcher.launch(intent)
		}
		catch (e: Exception) {
			e.printStackTrace()
			continuation.resume(false)
		}
	}

	override suspend fun showEnableBluetoothDialog(): Boolean = suspendCancellableCoroutine { continuation ->
		val launcher = createLauncher(
			contract = ActivityResultContracts.StartActivityForResult(),
			callback = { result ->
				continuation.resume(result.resultCode == Activity.RESULT_OK)
			},
		)
		launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
	}

	override suspend fun openLocationSettings(): Boolean = suspendCancellableCoroutine { continuation ->
		val launcher = createLauncher(
			contract = ActivityResultContracts.StartActivityForResult(),
			callback = {
				val locationManager = context.getSystemService<LocationManager>()
				val isLocationEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
						locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
				continuation.resume(isLocationEnabled)
			},
		)
		launcher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
	}

	override fun closeKeyboard() {
		val imm = context.getSystemService<InputMethodManager>()
		imm?.hideSoftInputFromWindow(activity.currentFocus?.windowToken, 0)
	}

	override fun isAppInBackground(): Boolean = _isAppInBackground

	override fun isAppClosed(): Boolean = _isAppClosed

	fun <I, O> createLauncher(
		contract: ActivityResultContract<I, O>,
		callback: (result: O) -> Unit,
	): ActivityResultLauncher<I> {
		var launcher: ActivityResultLauncher<I>? = null
		launcher = activity.activityResultRegistry.register(
			generateLauncherKey(),
			contract,
		) { result ->
			callback(result)
			launcher?.unregister()
		}
		return launcher
	}

	private fun generateLauncherKey(): String {
		return UUID.randomUUID().toString()
	}

	override fun onAppEnteredForeground() {
		_isAppInBackground = false
	}

	override fun onAppEnteredBackground() {
		_isAppInBackground = true
	}

	override fun brightnessFlow(): Flow<Int> {
		val flow by settingFlow(context, Settings.System.SCREEN_BRIGHTNESS, applicationScope, SettingFlowDelegate.Type.System)

		return flow.map { it.toIntOrNull() ?: 0 }
	}

	override fun lightSensorFlow(): Flow<Float> = callbackFlow {
		val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
		val lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

		if (lightSensor == null) {
			close(Exception("The device does not have light sensor"))
			return@callbackFlow
		}

		val listener = object : SensorEventListener {
			override fun onSensorChanged(event: SensorEvent?) {
				event?.let {
					trySend(it.values[0])
				}
			}

			override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {

			}
		}

		sensorManager.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_UI)

		awaitClose {
			sensorManager.unregisterListener(listener)
		}
	}.distinctUntilChanged()

	override fun startVibration() {
		val vibrator = context.getSystemService<Vibrator>() ?: return
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			// Continuous vibration (pattern: 0ms wait, 1000ms vibrate, repeat from index 0)
			vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 1000), 0))
		} else {
			@Suppress("DEPRECATION")
			vibrator.vibrate(longArrayOf(0, 1000), 0)
		}
	}

	override fun stopVibration() {
		val vibrator = context.getSystemService<Vibrator>() ?: return
		vibrator.cancel()
	}

	override fun setFlashlight(enabled: Boolean) {
		val cameraManager = context.getSystemService<CameraManager>() ?: return
		try {
			val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
			cameraManager.setTorchMode(cameraId, enabled)
		} catch (e: Exception) {
			e.printStackTrace()
		}
	}
}


// Some keys throws the following exception when the app is installed from an apk (not from the editor):
// java.lang.SecurityException: Settings key: <bluetooth_name> is only readable to apps with targetSdkVersion lower than or equal to: 31
private class SettingFlowDelegate(
	private val context: Context,
	private val key: String,
	private val scope: CoroutineScope,
	private val type: Type,
) : ReadOnlyProperty<Any?, StateFlow<String>> {

	private val flow by lazy {
		callbackFlow {
			val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
				override fun onChange(selfChange: Boolean, uri: Uri?) {
					val value = when (type) {
						Type.Global -> Settings.Global.getString(context.contentResolver, key)
						Type.System -> Settings.System.getString(context.contentResolver, key)
						Type.Secure -> Settings.Secure.getString(context.contentResolver, key)
					}
					if (value != null) {
						trySend(value)
					}

					println("$$$ key: $key, value: $value")
				}
			}

			val value = when (type) {
				Type.Global -> Settings.Global.getString(context.contentResolver, key)
				Type.System -> Settings.System.getString(context.contentResolver, key)
				Type.Secure -> Settings.Secure.getString(context.contentResolver, key)
			}
			if (value != null) {
				trySend(value)
			}

			val uri = when (type) {
				Type.Global -> Settings.Global.getUriFor(key)
				Type.System -> Settings.System.getUriFor(key)
				Type.Secure -> Settings.Secure.getUriFor(key)
			}
			context.contentResolver.registerContentObserver(uri, false, observer)

			awaitClose {
				context.contentResolver.unregisterContentObserver(observer)
			}
		}.stateIn(
			scope = scope,
			started = SharingStarted.WhileSubscribed(0),
			initialValue = Settings.Secure.getString(context.contentResolver, key) ?: "",
		)
	}

	override fun getValue(thisRef: Any?, property: KProperty<*>): StateFlow<String> = flow

	enum class Type {
		Global,
		System,
		Secure,
	}
}

private fun settingFlow(
	context: Context,
	key: String,
	scope: CoroutineScope,
	type: SettingFlowDelegate.Type,
): ReadOnlyProperty<Any?, StateFlow<String>> {
	return SettingFlowDelegate(context, key, scope, type)
}
