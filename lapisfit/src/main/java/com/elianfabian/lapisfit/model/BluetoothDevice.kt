package com.elianfabian.lapisfit.model

data class BluetoothDevice(
	val address: String,
	val name: String?,
	val pairingState: PairingState,
	val connectionState: ConnectionState,
) {
	enum class PairingState {
		None,
		Pairing,
		Paired;

		val isPaired: Boolean get() = this == Paired
	}

	enum class ConnectionState {
		Connected,
		Connecting,
		Disconnected,
		Disconnecting,
	}
}
