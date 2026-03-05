package com.elianfabian.lapisbt.app.common.presentation.model

data class BluetoothMessage(
	val senderName: String?,
	val senderAddress: String,
	val content: String,
	//val isFromLocalUser: Boolean,
	val isRead: Boolean,
)
