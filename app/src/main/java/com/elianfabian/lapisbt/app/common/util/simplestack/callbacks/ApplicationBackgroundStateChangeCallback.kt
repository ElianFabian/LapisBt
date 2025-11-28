package com.elianfabian.lapisbt.app.common.util.simplestack.callbacks

interface ApplicationBackgroundStateChangeCallback {

	fun onAppEnteredForeground()
	fun onAppEnteredBackground()
}
