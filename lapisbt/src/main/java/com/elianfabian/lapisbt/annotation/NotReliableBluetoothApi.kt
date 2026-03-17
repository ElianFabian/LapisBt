package com.elianfabian.lapisbt.annotation

@RequiresOptIn(
	message = "This API uses depends on hidden details of the BluetoothApi, and may break in future Android versions or not even be reliable at all.",
	level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
	AnnotationTarget.CLASS,
	AnnotationTarget.FUNCTION,
	AnnotationTarget.PROPERTY,
)
public annotation class NotReliableBluetoothApi
