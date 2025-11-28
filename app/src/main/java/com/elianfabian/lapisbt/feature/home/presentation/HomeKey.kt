package com.elianfabian.lapisbt.feature.home.presentation

import com.elianfabian.lapisbt.app.common.util.simplestack.FragmentKey
import kotlinx.parcelize.Parcelize

@Parcelize
data object HomeKey : FragmentKey(
	serviceModule = null
) {
	override fun instantiateFragment() = HomeFragment()
}
