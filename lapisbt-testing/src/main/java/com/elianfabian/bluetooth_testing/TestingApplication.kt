package com.elianfabian.bluetooth_testing

import android.app.Application
import com.elianfabian.lapisbt.LapisBt

class TestingApplication : Application() {

	lateinit var lapisBt: LapisBt
		private set


	override fun onCreate() {
		super.onCreate()

		lapisBt = LapisBt.newInstance(this)
	}
}

val Application.lapisBt: LapisBt get() = (this as TestingApplication).lapisBt
