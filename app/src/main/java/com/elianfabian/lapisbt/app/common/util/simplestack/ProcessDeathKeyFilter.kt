package com.elianfabian.lapisbt.app.common.util.simplestack

import com.zhuinden.simplestack.KeyFilter

class ProcessDeathKeyFilter : KeyFilter {

	override fun filterHistory(restoredKeys: List<Any>): List<Any> {
		return restoredKeys
	}
}
