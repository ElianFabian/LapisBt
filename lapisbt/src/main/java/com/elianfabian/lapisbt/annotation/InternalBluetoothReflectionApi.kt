package com.elianfabian.lapisbt.annotation

@RequiresOptIn(
	message = "This API uses reflection on internal Bluetooth APIs and may break in future Android versions.",
	level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
	AnnotationTarget.CLASS,
	AnnotationTarget.FUNCTION,
	AnnotationTarget.PROPERTY,
)
public annotation class InternalBluetoothReflectionApi
