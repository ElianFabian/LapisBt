package com.elianfabian.lapisbt.app

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import com.elianfabian.lapisbt.app.common.util.simplestack.FragmentStateChanger
import com.elianfabian.lapisbt.app.common.util.simplestack.ProcessDeathKeyFilter
import com.elianfabian.lapisbt.app.common.util.simplestack.callbacks.MainActivityCallbacks
import com.elianfabian.lapisbt.app.common.util.simplestack.callbacks.OnMainBackstackIsInitializedCallback
import com.elianfabian.lapisbt.app.common.util.simplestack.forEachServiceOfType
import com.elianfabian.lapisbt.app.ui.theme.LapisBtTheme
import com.elianfabian.lapisbt.feature.home.presentation.HomeKey
import com.zhuinden.simplestack.BackHandlingModel
import com.zhuinden.simplestack.History
import com.zhuinden.simplestack.SimpleStateChanger
import com.zhuinden.simplestack.navigator.Navigator
import com.zhuinden.simplestackextensions.lifecyclektx.observeAheadOfTimeWillHandleBackChanged
import com.zhuinden.simplestackextensions.navigatorktx.backstack
import com.zhuinden.simplestackextensions.services.DefaultServiceProvider

class MainActivity : FragmentActivity() {

	private val backPressedCallback = object : OnBackPressedCallback(false) {
		override fun handleOnBackPressed() {
			backstack.goBack()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()

		onBackPressedDispatcher.addCallback(backPressedCallback)

		// On some devices (at least on POCO F5 Pro API 35) the navbar is not completely transparent, this way we can force it
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			window.isNavigationBarContrastEnforced = false
		}

		setContent {
			LapisBtTheme {
				LapisBtApp { innerPadding ->
					FragmentScreenContainer(
						innerPadding = innerPadding,
						modifier = Modifier
							.fillMaxWidth()
					)
				}
			}
		}

		val fragmentStateChanger = FragmentStateChanger(supportFragmentManager, fragmentContainerView.id)

		val globalServicesProvider = (application as BluetoothApplication).globalServicesProvider

		Navigator.configure()
			.setBackHandlingModel(BackHandlingModel.AHEAD_OF_TIME)
			.setStateChanger(
				SimpleStateChanger { stateChange ->
					fragmentStateChanger.handleStateChange(stateChange)
				}
			)
			.setScopedServices(DefaultServiceProvider())
			.setGlobalServices { backstack ->
				globalServicesProvider.create(backstack, this)
			}
			.setKeyFilter(ProcessDeathKeyFilter())
			.install(
				this,
				fragmentContainerView,
				History.single(HomeKey),
			)

		backPressedCallback.isEnabled = backstack.willHandleAheadOfTimeBack()
		backstack.observeAheadOfTimeWillHandleBackChanged(this) { willHandleBack ->
			backPressedCallback.isEnabled = willHandleBack
		}

		backstack.forEachServiceOfType<OnMainBackstackIsInitializedCallback> { service ->
			service.onMainBackstackIsInitialized(backstack)
		}

		backstack.forEachServiceOfType<MainActivityCallbacks> { service ->
			service.onCreateMainActivity(this)
		}
	}


	/**
	 * This FrameLayout is the container used to implement Fragment based
	 * navigation in Simple-Stack with Compose based UI.
	 */
	private val fragmentContainerView by lazy {
		InnerPaddingFrameLayout(this).apply {
			id = R.id.MainFragmentContainerView
		}
	}

	@Suppress("UNCHECKED_CAST")
	override fun <T : View?> findViewById(id: Int): T {
		if (id == fragmentContainerView.id) {
			return fragmentContainerView as T
		}
		return super.findViewById(id)
	}

	@Composable
	private fun FragmentScreenContainer(
		innerPadding: PaddingValues,
		modifier: Modifier = Modifier,
	) {
		AndroidView(
			factory = { fragmentContainerView },
			update = { container ->
				container.innerPadding.value = innerPadding
			},
			modifier = modifier
		)
	}
}


/**
 * FrameLayout that contains the inner padding provided by a Scaffold.
 *
 * This is to allow Fragments access this value.
 */
private class InnerPaddingFrameLayout(context: Context) : FrameLayout(context), InnerPaddingProvider {
	override var innerPadding = mutableStateOf(PaddingValues())
}

/**
 * Interface that allows to access the inner padding from the App's Scaffold.
 */
interface InnerPaddingProvider {
	val innerPadding: State<PaddingValues>
}
