package com.elianfabian.lapisbt.feature.manual_bluetooth_communication.presentation

import com.elianfabian.lapisbt.app.common.util.simplestack.FragmentKey
import com.elianfabian.lapisbt.feature.manual_bluetooth_communication.di.ManualBluetoothCommunicationModule
import kotlinx.parcelize.Parcelize

@Parcelize
data object ManualBluetoothCommunicationKey : FragmentKey(
	serviceModule = ManualBluetoothCommunicationModule,
) {
	override fun instantiateFragment() = ManualBluetoothCommunicationFragment()
}
