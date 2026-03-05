package com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.presentation

import androidx.fragment.app.Fragment
import com.elianfabian.lapisbt.app.common.util.simplestack.FragmentKey
import com.elianfabian.lapisbt.feature.api_based_bluetooth_communication.di.ApiBasedBluetoothCommunicationModule
import kotlinx.parcelize.Parcelize

@Parcelize
data object ApiBasedBluetoothCommunicationKey : FragmentKey(
	serviceModule = ApiBasedBluetoothCommunicationModule,
) {

	override fun instantiateFragment() = ApiBasedBluetoothCommunicationFragment()
}
