package com.elianfabian.lapisbt.model

import java.util.UUID

public data class BluetoothDevice(
	val address: String,
	val name: String?,
	val addressType: AddressType,
	val type: Type,
	val mode: Mode,
	val uuids: List<UUID>,
	val pairingState: PairingState,
	val connectionState: ConnectionState,
) {
	public enum class PairingState {
		None,
		Pairing,
		Paired;

		public val isPaired: Boolean get() = this == Paired
	}

	public enum class ConnectionState {
		Connected,
		Connecting,
		Disconnected,
		Disconnecting,
	}

	public enum class AddressType {
		Public,
		Random,
		Anonymous,
		Unknown,
		NotSupported,
	}

	public enum class Type {
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

	public enum class Mode {
		Classic,
		Le,
		Dual,
		Unknown
	}
}
