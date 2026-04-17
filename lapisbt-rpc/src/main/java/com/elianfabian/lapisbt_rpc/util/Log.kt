package com.elianfabian.lapisbt_rpc.util

import android.util.Log

internal const val LOG_ENABLED = false


@Suppress("NOTHING_TO_INLINE")
internal inline fun logDebug(tag: String, message: String) {
	if (LOG_ENABLED) {
		Log.d(tag, message)
	}
}
