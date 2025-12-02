package com.elianfabian.lapisbt.model

import java.util.UUID

public data class BluetoothDevice(
	val address: String,
	val name: String?,
	val alias: String?,
	val addressType: AddressType,
	val majorDeviceClass: MajorDeviceClass,
	val type: Type,
	val uuids: List<UUID>?,
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

	public enum class MajorDeviceClass {
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

	public enum class Type {
		Classic,
		Le,
		Dual,
		Unknown
	}
}
