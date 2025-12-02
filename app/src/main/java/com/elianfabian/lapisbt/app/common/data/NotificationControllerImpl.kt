package com.elianfabian.lapisbt.app.common.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.elianfabian.lapisbt.app.common.data.service.BluetoothService
import com.elianfabian.lapisbt.app.common.domain.NotificationController
import com.elianfabian.lapisbt.app.BuildConfig
import com.elianfabian.lapisbt.app.MainActivity
import com.elianfabian.lapisbt.app.R
import com.elianfabian.lapisbt.app.common.util.simplestack.callbacks.OnCreateApplicationCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class NotificationControllerImpl(
	private val context: Context,
	private val applicationScope: CoroutineScope,
) : NotificationController,
	OnCreateApplicationCallback {

	private val _notificationManager = context.getSystemService<NotificationManager>()!!


	private val _events = MutableSharedFlow<NotificationController.NotificationEvent>()
	override val events = _events.asSharedFlow()


	private val _onStopApplicationBroadcastReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			if (intent.action != ACTION_STOP_APPLICATION) {
				return
			}
			applicationScope.launch {
				_events.emit(NotificationController.NotificationEvent.OnStopApplication)
			}
		}
	}

	private val _onDismissApplicationIsRunningNotificationBroadcastReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			if (intent.action != ACTION_DISMISS_APPLICATION_IS_RUNNING_NOTIFICATION) {
				return
			}
			applicationScope.launch {
				_events.emit(NotificationController.NotificationEvent.OnDismissApplicationIsRunningNotification)
			}
		}
	}

	private val _remoteInputMessageBroadcastReceiver = object : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			if (intent.action != ACTION_SEND_MESSAGE_FROM_REMOTE_INPUT) {
				return
			}

			val remoteInput = RemoteInput.getResultsFromIntent(intent) ?: return
			val message = remoteInput.getCharSequence(REMOTE_INPUT_RESULT_KEY).toString()

			applicationScope.launch {
				_events.emit(NotificationController.NotificationEvent.OnReceiveMessageFromRemoteInput(message))
			}
		}
	}

	override fun onCreateApplication() {
		ContextCompat.registerReceiver(
			context,
			_onStopApplicationBroadcastReceiver,
			IntentFilter(ACTION_STOP_APPLICATION),
			ContextCompat.RECEIVER_NOT_EXPORTED,
		)
		ContextCompat.registerReceiver(
			context,
			_onDismissApplicationIsRunningNotificationBroadcastReceiver,
			IntentFilter(ACTION_DISMISS_APPLICATION_IS_RUNNING_NOTIFICATION),
			ContextCompat.RECEIVER_NOT_EXPORTED,
		)
		ContextCompat.registerReceiver(
			context,
			_remoteInputMessageBroadcastReceiver,
			IntentFilter(ACTION_SEND_MESSAGE_FROM_REMOTE_INPUT),
			ContextCompat.RECEIVER_NOT_EXPORTED,
		)

		if (Build.VERSION.SDK_INT >= 26) {
			val mainChannel = NotificationChannel(
				Channel.Main,
				"Main",
				NotificationManager.IMPORTANCE_HIGH,
			)
			_notificationManager.createNotificationChannel(mainChannel)

			val messagesChannel = NotificationChannel(
				Channel.Messages,
				"Messages",
				NotificationManager.IMPORTANCE_HIGH,
			)
			_notificationManager.createNotificationChannel(messagesChannel)
		}
	}

	@Suppress("DEPRECATION")
	override fun sendGroupNotificationMessage(message: NotificationController.GroupMessageNotification) {
		val oldNotification = _notificationManager.activeNotifications.firstOrNull { it.id == 2 }?.notification

		val androidNotification = NotificationCompat.Builder(context, Channel.Messages).apply {
			setSmallIcon(R.drawable.ic_launcher_foreground)
//			setOnlyAlertOnce(true)
			setPriority(NotificationCompat.PRIORITY_HIGH)
			setContentIntent(
				PendingIntent.getActivity(
					context,
					0,
					Intent(context, MainActivity::class.java).apply {
						flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
					},
					PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
				)
			)

			setContentTitle("Message from: ${message.senderName}")
			setContentText("Message from: ${message.content}")

			// Reusing the old style allows us to have the proper append animation when adding a new message
			val style = if (oldNotification != null) {
				NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(oldNotification)
			}
			else {
				NotificationCompat.MessagingStyle("")
			}

			style?.also { style ->
				style.isGroupConversation = true
				style.setConversationTitle("Bluetooth Group")
				val person = Person.Builder()
					.setName(message.senderName)
					.build()

				style.addMessage(
					message.content,
					System.currentTimeMillis(),
					person,
				)
			}
			setStyle(style)

			val remoteInput = RemoteInput.Builder(REMOTE_INPUT_RESULT_KEY)
				.setLabel("Send a message...")
				.build()

			addAction(
				NotificationCompat.Action.Builder(
					0,
					"Reply",
					PendingIntent.getBroadcast(
						context,
						0,
						Intent(ACTION_SEND_MESSAGE_FROM_REMOTE_INPUT).apply {
							setPackage(context.packageName)
						},
						PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE or if (Build.VERSION.SDK_INT >= 34) {
							PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
						}
						else 0,
					),
				).addRemoteInput(remoteInput).build()
			)

		}.build()

		_notificationManager.notify(2, androidNotification)
	}

	override fun stopLoadingGroupNotification() {
		val oldNotification = _notificationManager.activeNotifications.firstOrNull { it.id == 2 }?.notification
		_notificationManager.notify(2, oldNotification)
	}

	override fun dismissGroupNotification() {
		_notificationManager.cancel(2)
	}

	override fun showApplicationIsRunningNotification() {
		context.bindService(
			Intent(context, BluetoothService::class.java),
			object : ServiceConnection {
				override fun onServiceConnected(name: ComponentName, service: IBinder) {
					service as BluetoothService.BluetoothBinder
					val bluetoothService = service.getService()

					val notification = NotificationCompat.Builder(context, Channel.Main)
						.setContentTitle("Bluetooth-Chat")
						.setContentText("App is running")
						.setOngoing(true)
						// I think for API level +33 small icon is required to be able to show the title and the text
						.setSmallIcon(R.drawable.ic_launcher_foreground)
						.addAction(
							0,
							"Stop",
							PendingIntent.getBroadcast(
								context,
								0,
								Intent(ACTION_STOP_APPLICATION).apply {
									setPackage(context.packageName)
								},
								PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
							),
						)
						.setDeleteIntent(
							// For Broadcast Receivers to work the need to be "explicit and defined in the Manifest" or "implicit and dynamic"
							PendingIntent.getBroadcast(
								context,
								0,
								Intent(ACTION_DISMISS_APPLICATION_IS_RUNNING_NOTIFICATION).apply {
									setPackage(context.packageName)
								},
								PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
							)
						)
						.setContentIntent(
							PendingIntent.getActivity(
								context,
								0,
								Intent(context, MainActivity::class.java).apply {
									flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
								},
								PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
							)
						)
						.build()

					bluetoothService.startForeground(1, notification)
				}

				override fun onServiceDisconnected(name: ComponentName) {

				}
			},
			Context.BIND_AUTO_CREATE,
		)
	}


	companion object {
		private const val ACTION_STOP_APPLICATION = "${BuildConfig.APPLICATION_ID}.ACTION_STOP_APPLICATION"
		private const val ACTION_DISMISS_APPLICATION_IS_RUNNING_NOTIFICATION = "${BuildConfig.APPLICATION_ID}.ACTION_DISMISS_APPLICATION_IS_RUNNING_NOTIFICATION"
		private const val ACTION_SEND_MESSAGE_FROM_REMOTE_INPUT = "${BuildConfig.APPLICATION_ID}.ACTION_SEND_MESSAGE_FROM_REMOTE_INPUT"
		private const val REMOTE_INPUT_RESULT_KEY = "REMOTE_INPUT_RESULT_KEY"

		object Channel {
			const val Main = "channel_main"
			const val Messages = "channel_messages"
		}
	}
}
