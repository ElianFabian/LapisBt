package com.elianfabian.lapisbt.model

import java.util.UUID

data class BluetoothDevice(
	val address: String,
	val name: String?,
	val type: Type,
	val mode: Mode,
	val uuids: List<UUID>,
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

	enum class Type {
		Misc,
		Computer,
		Phone,
		Networking,
		AudioVideo,
		Peripheral,
		Imaging,
		Wearable,
		Toy,
		Health,
		Uncategorized,
	}

	enum class Mode {
		Classic,
		Le,
		Dual,
		Unknown
	}
}
