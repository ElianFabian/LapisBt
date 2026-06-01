package com.elianfabian.lapisbt.app.common.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.elianfabian.lapisbt.app.common.util.simplestack.compose.BasePreview

data class PermissionDialogState(
	val title: String,
	val message: String,
	val actionName: String,
	val onAction: () -> Unit,
	val onDismissRequest: () -> Unit,
)

@Composable
fun PermissionDialog(
	state: PermissionDialogState,
) {
	Dialog(
		properties = DialogProperties(
			dismissOnBackPress = true,
			usePlatformDefaultWidth = true,
		),
		onDismissRequest = state.onDismissRequest
	) {
		Card {
			Column(
				modifier = Modifier.padding(16.dp)
			) {
				Text(
					text = state.title,
					fontSize = 18.sp,
					fontWeight = FontWeight.SemiBold,
				)
				Spacer(Modifier.height(8.dp))
				Text(
					text = state.message,
				)
				Spacer(modifier = Modifier.height(16.dp))
				Button(
					onClick = state.onAction,
					modifier = Modifier.fillMaxWidth()
				) {
					Text(state.actionName)
				}
			}
		}
	}
}

@Preview(showBackground = true)
@Composable
private fun PermissionDialogPreview() = BasePreview {
	PermissionDialog(
		state = PermissionDialogState(
			title = "Permission Required",
			message = "This app needs Bluetooth permissions to scan for nearby devices. Please enable them in settings.",
			actionName = "Go to Settings",
			onAction = {},
			onDismissRequest = {}
		)
	)
}
