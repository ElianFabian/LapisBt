package com.elianfabian.lapisbt.app.common.data

import android.content.Context
import com.elianfabian.bluetoothchatapp_prototype.common.domain.AndroidHelper
import com.elianfabian.bluetoothchatapp_prototype.common.domain.MultiplePermissionController
import com.elianfabian.bluetoothchatapp_prototype.common.domain.NotificationController
import com.elianfabian.bluetoothchatapp_prototype.common.domain.allAreGranted
import com.elianfabian.lapisbt.app.common.util.simplestack.callbacks.ApplicationBackgroundStateChangeCallback
import com.elianfabian.lapisbt.app.common.util.simplestack.callbacks.OnCreateApplicationCallback
import com.zhuinden.simplestack.ScopedServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ApplicationOrchestrator(
	private val context: Context,
	private val androidHelper: AndroidHelper,
	private val bluetoothPermissionController: MultiplePermissionController,
	private val notificationController: NotificationController,
	private val applicationScope: CoroutineScope,
) : OnCreateApplicationCallback,
	ApplicationBackgroundStateChangeCallback,
	ScopedServices.Registered {

	override fun onCreateApplication() {
		applicationScope.launch {
			bluetoothPermissionController.state.first {
				it.allAreGranted
			}

			notificationController.showApplicationIsRunningNotification()
		}
		applicationScope.launch {
			notificationController.events.collect { event ->
				when (event) {
					is NotificationController.NotificationEvent.OnStopApplication -> {
						androidHelper.stopApplication()
					}
					is NotificationController.NotificationEvent.OnDismissApplicationIsRunningNotification -> {
						notificationController.showApplicationIsRunningNotification()
					}
					is NotificationController.NotificationEvent.OnReceiveMessageFromRemoteInput -> {
						// no-op
					}
				}
			}
		}
	}

	override fun onServiceRegistered() {
		notificationController.dismissGroupNotification()
	}

	override fun onServiceUnregistered() {

	}

	override fun onAppEnteredForeground() {
		notificationController.dismissGroupNotification()
	}

	override fun onAppEnteredBackground() {

	}
}
