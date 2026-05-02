package com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.presentation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elianfabian.lapisbt.app.common.util.simplestack.compose.ComposeKeyedFragment
import com.zhuinden.simplestackcomposeintegration.services.rememberService

class ApiBasedBluetoothCommunicationFragment : ComposeKeyedFragment() {

	@Composable
	override fun Content(innerPadding: PaddingValues) {
		val viewModel = rememberService<ApiBasedBluetoothCommunicationViewModel>()
		val state by viewModel.state.collectAsStateWithLifecycle()

		ApiBasedBluetoothCommunicationScreen(
			state = state,
			onAction = { action ->
				viewModel.sendAction(action)
			},
		)
	}
}
