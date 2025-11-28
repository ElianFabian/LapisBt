package com.elianfabian.bluetoothchatapp_prototype.common.domain

import kotlinx.coroutines.flow.StateFlow

interface PermissionController {
	val state: StateFlow<PermissionState>

	suspend fun request(): PermissionState
}

interface MultiplePermissionController {
	val state: StateFlow<Map<String, PermissionState>>

	suspend fun request(): Map<String, PermissionState>
}

enum class PermissionState {
	NotDetermined,
	Granted,
	Denied,
	PermanentlyDenied;

	val isGranted: Boolean get() = this == Granted
}

val Map<String, PermissionState>.allAreGranted: Boolean
	get() = this.values.all { it.isGranted } || this.isEmpty()

val Map<String, PermissionState>.allArePermanentlyDenied: Boolean
	get() = this.values.all { it == PermissionState.PermanentlyDenied } && this.isNotEmpty()
