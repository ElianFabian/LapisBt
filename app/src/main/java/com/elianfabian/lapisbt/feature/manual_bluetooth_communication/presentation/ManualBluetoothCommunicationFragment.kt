package com.elianfabian.lapisbt.feature.manual_bluetooth_communication.presentation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elianfabian.lapisbt.app.common.util.simplestack.compose.ComposeKeyedFragment
import com.zhuinden.simplestackcomposeintegration.services.rememberService

class ManualBluetoothCommunicationFragment : ComposeKeyedFragment() {
	@Composable
	override fun Content(innerPadding: PaddingValues) {

		val viewModel = rememberService<ManualBluetoothCommunicationViewModel>()
		val state by viewModel.state.collectAsStateWithLifecycle()

		ManualBluetoothCommunicationScreen(
			state = state,
			onAction = { action ->
				viewModel.sendAction(action)
			}
		)
	}
}
