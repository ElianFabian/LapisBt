package com.elianfabian.lapisbt.app.common.presentation.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elianfabian.lapisbt.app.common.util.simplestack.compose.BasePreview

@Composable
fun EnableBluetoothPlaceholder(
	onEnableClick: () -> Unit,
	modifier: Modifier = Modifier,
) {
	val infiniteTransition = rememberInfiniteTransition(label = "pulse")

	// Wave 1
	val wave1Scale by infiniteTransition.animateFloat(
		initialValue = 1f,
		targetValue = 2f,
		animationSpec = infiniteRepeatable(
			animation = tween(2000, easing = LinearEasing),
			repeatMode = RepeatMode.Restart
		),
		label = "wave1Scale",
	)
	val wave1Alpha by infiniteTransition.animateFloat(
		initialValue = 0.4f,
		targetValue = 0f,
		animationSpec = infiniteRepeatable(
			animation = tween(2000, easing = LinearEasing),
			repeatMode = RepeatMode.Restart
		),
		label = "wave1Alpha",
	)

	// Wave 2 (delayed)
	val wave2Scale by infiniteTransition.animateFloat(
		initialValue = 1f,
		targetValue = 2f,
		animationSpec = infiniteRepeatable(
			animation = tween(2000, easing = LinearEasing),
			repeatMode = RepeatMode.Restart,
			initialStartOffset = StartOffset(1000),
		),
		label = "wave2Scale",
	)
	val wave2Alpha by infiniteTransition.animateFloat(
		initialValue = 0.4f,
		targetValue = 0f,
		animationSpec = infiniteRepeatable(
			animation = tween(2000, easing = LinearEasing),
			repeatMode = RepeatMode.Restart,
			initialStartOffset = StartOffset(1000),
		),
		label = "wave2Alpha",
	)

	Box(
		contentAlignment = Alignment.Center,
		modifier = modifier
			.fillMaxSize()
			.padding(32.dp)
	) {
		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.Center
		) {
			val hapticFeedback = LocalHapticFeedback.current
			
			Box(contentAlignment = Alignment.Center) {
				// Animated ripple waves
				Box(
					modifier = Modifier
						.graphicsLayer {
							scaleX = wave1Scale
							scaleY = wave1Scale
							alpha = wave1Alpha
						}
						.size(100.dp)
						.clip(CircleShape)
						.background(MaterialTheme.colorScheme.primary)
				)
				Box(
					modifier = Modifier
						.graphicsLayer {
							scaleX = wave2Scale
							scaleY = wave2Scale
							alpha = wave2Alpha
						}
						.size(100.dp)
						.clip(CircleShape)
						.background(MaterialTheme.colorScheme.primary)
				)

				// Core Icon Circle
				Box(
					contentAlignment = Alignment.Center,
					modifier = Modifier
						.size(100.dp)
						.clip(CircleShape)
						.background(MaterialTheme.colorScheme.primary)
						.clickable {
							hapticFeedback.performHapticFeedback(HapticFeedbackType.ContextClick)
							onEnableClick()
						}
				) {
					Icon(
						imageVector = Icons.Default.Bluetooth,
						contentDescription = null,
						tint = MaterialTheme.colorScheme.onPrimary,
						modifier = Modifier.size(56.dp)
					)
				}
			}

			Spacer(Modifier.height(48.dp))

			Text(
				text = "Bluetooth is Off",
				fontSize = 22.sp,
				fontWeight = FontWeight.Bold,
				color = MaterialTheme.colorScheme.onSurface
			)

			Spacer(Modifier.height(12.dp))

			Text(
				text = "Tap the icon to enable Bluetooth and start connecting with nearby devices.",
				fontSize = 15.sp,
				textAlign = TextAlign.Center,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.padding(horizontal = 24.dp)
			)
		}
	}
}

@Preview(showBackground = true)
@Composable
private fun EnableBluetoothPlaceholderPreview() = BasePreview {
	EnableBluetoothPlaceholder(onEnableClick = {})
}
