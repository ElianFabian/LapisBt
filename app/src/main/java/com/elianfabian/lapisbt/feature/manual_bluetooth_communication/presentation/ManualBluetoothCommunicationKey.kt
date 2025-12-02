package com.elianfabian.lapisbt.feature.manual_bluetooth_communication.presentation

import com.elianfabian.lapisbt.app.common.util.simplestack.FragmentKey
import kotlinx.parcelize.Parcelize

@Parcelize
data object ManualBluetoothCommunicationKey : FragmentKey(
	serviceModule = null
) {
	override fun instantiateFragment() = ManualBluetoothCommunicationFragment()
}
