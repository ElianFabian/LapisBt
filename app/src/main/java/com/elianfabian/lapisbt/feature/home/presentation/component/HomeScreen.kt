package com.elianfabian.lapisbt.feature.home.presentation.component

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.elianfabian.lapisbt.app.common.util.simplestack.compose.BasePreview
import com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.presentation.ApiBasedBluetoothCommunicationKey
import com.elianfabian.lapisbt.feature.manual_bluetooth_communication.presentation.ManualBluetoothCommunicationKey
import com.zhuinden.simplestackcomposeintegration.core.LocalBackstack

@Composable
fun HomeScreen(
	modifier: Modifier = Modifier,
) {
	val backstack = LocalBackstack.current

	Column(
		verticalArrangement = Arrangement.Center,
		horizontalAlignment = Alignment.CenterHorizontally,
		modifier = modifier
			.fillMaxSize()
	) {
		Button(
			onClick = {
				backstack.goTo(ManualBluetoothCommunicationKey)
			}
		) {
			Text("Manual Bluetooth Communication")
		}
		Spacer(Modifier.height(8.dp))
		Button(
			onClick = {
				backstack.goTo(ApiBasedBluetoothCommunicationKey)
			}
		) {
			Text("API-Based Bluetooth Communication")
		}
	}
}


@Preview(
	showBackground = true,
	showSystemUi = true,
	uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun Preview() = BasePreview {
	HomeScreen()
}
