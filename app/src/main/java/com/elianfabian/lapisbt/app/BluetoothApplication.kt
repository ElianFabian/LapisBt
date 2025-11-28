package com.elianfabian.lapisbt.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.elianfabian.lapisbt.app.common.di.GlobalServiceProvider
import com.elianfabian.lapisbt.app.common.util.simplestack.callbacks.ApplicationBackgroundStateChangeCallback
import com.elianfabian.lapisbt.app.common.util.simplestack.callbacks.OnCreateApplicationCallback
import com.elianfabian.lapisbt.app.common.util.simplestack.callbacks.OnMainBackstackIsInitializedCallback
import com.elianfabian.lapisbt.app.common.util.simplestack.forEachServiceOfType
import com.zhuinden.simplestack.Backstack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BluetoothApplication : Application(), OnMainBackstackIsInitializedCallback {

	private val _applicationScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

	private var _mainBackstack = MutableStateFlow<Backstack?>(null)
	val mainBackstack get() = _mainBackstack.value ?: throw IllegalStateException("Main Backstack is not yet initialized")

	val globalServicesProvider = GlobalServiceProvider(this)

	private val _processLifecycleObserver = object : DefaultLifecycleObserver {
		override fun onStart(owner: LifecycleOwner) {
			mainBackstack.forEachServiceOfType<ApplicationBackgroundStateChangeCallback> { service ->
				service.onAppEnteredForeground()
			}
		}

		override fun onStop(owner: LifecycleOwner) {
			mainBackstack.forEachServiceOfType<ApplicationBackgroundStateChangeCallback> { service ->
				service.onAppEnteredBackground()
			}
		}
	}


	override fun onCreate() {
		super.onCreate()

		_applicationScope.launch {
			val mainBackstack = _mainBackstack.filterNotNull().first()
			mainBackstack.forEachServiceOfType<OnCreateApplicationCallback> { service ->
				service.onCreateApplication()
			}
		}
	}


	override fun onMainBackstackIsInitialized(backstack: Backstack) {
		_mainBackstack.value = backstack
		ProcessLifecycleOwner.get().lifecycle.addObserver(_processLifecycleObserver)
	}
}
