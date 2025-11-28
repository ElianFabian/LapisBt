package com.elianfabian.bluetoothchatapp_prototype.common.domain

import kotlinx.coroutines.flow.SharedFlow

interface NotificationController {

	val events: SharedFlow<NotificationEvent>

	fun sendGroupNotificationMessage(
		message: GroupMessageNotification,
	)

	fun stopLoadingGroupNotification()

	fun dismissGroupNotification()

	fun showApplicationIsRunningNotification()

	data class GroupMessageNotification(
		val senderName: String,
		val content: String,
	)

	sealed interface NotificationEvent {
		data object OnStopApplication : NotificationEvent
		data object OnDismissApplicationIsRunningNotification : NotificationEvent
		data class OnReceiveMessageFromRemoteInput(val message: String) : NotificationEvent
	}
}
