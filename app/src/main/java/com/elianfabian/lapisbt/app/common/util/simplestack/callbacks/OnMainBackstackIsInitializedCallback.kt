package com.elianfabian.lapisbt.app.common.util.simplestack.callbacks

import com.zhuinden.simplestack.Backstack

interface OnMainBackstackIsInitializedCallback {
	fun onMainBackstackIsInitialized(backstack: Backstack)
}
