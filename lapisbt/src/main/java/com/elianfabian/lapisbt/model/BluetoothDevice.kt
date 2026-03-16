package com.elianfabian.lapisbt.model

import java.util.UUID

public data class BluetoothDevice(
	val address: String,
	val name: String?,
	val alias: String?,
	val addressType: AddressType,
	val deviceClass: DeviceClass,
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

	public sealed interface DeviceClass {

		public data object Uncategorized : DeviceClass
		public data class Unknown(val value: Int) : DeviceClass

		public sealed interface Computer : DeviceClass {
			public data object Uncategorized : Computer
			public data object Desktop : Computer
			public data object Server : Computer
			public data object Laptop : Computer
			public data object HandheldPcPda : Computer
			public data object PalmSizePcPda : Computer
			public data object Wearable : Computer
			public data class Unknown(val value: Int) : Computer
		}

		public sealed interface Phone : DeviceClass {
			public data object Uncategorized : Phone
			public data object Cellular : Phone
			public data object Cordless : Phone
			public data object Smart : Phone
			public data object ModemOrGateway : Phone
			public data object Isdn : Phone
			public data class Unknown(val value: Int) : Phone
		}

		public sealed interface AudioVideo : DeviceClass {
			public data object Uncategorized : AudioVideo
			public data object WearableHeadset : AudioVideo
			public data object Handsfree : AudioVideo
			public data object Microphone : AudioVideo
			public data object Loudspeaker : AudioVideo
			public data object Headphones : AudioVideo
			public data object PortableAudio : AudioVideo
			public data object CarAudio : AudioVideo
			public data object SetTopBox : AudioVideo
			public data object HifiAudio : AudioVideo
			public data object Vcr : AudioVideo
			public data object VideoCamera : AudioVideo
			public data object Camcorder : AudioVideo
			public data object VideoMonitor : AudioVideo
			public data object VideoDisplayAndLoudspeaker : AudioVideo
			public data object VideoConferencing : AudioVideo
			public data object VideoGamingToy : AudioVideo
			public data class Unknown(val value: Int) : AudioVideo
		}

		public sealed interface Wearable : DeviceClass {
			public data object Uncategorized : Wearable
			public data object WristWatch : Wearable
			public data object Pager : Wearable
			public data object Jacket : Wearable
			public data object Helmet : Wearable
			public data object Glasses : Wearable
			public data class Unknown(val value: Int) : Wearable
		}

		public sealed interface Toy : DeviceClass {
			public data object Uncategorized : Toy
			public data object Robot : Toy
			public data object Vehicle : Toy
			public data object DollActionFigure : Toy
			public data object Controller : Toy
			public data object Game : Toy
			public data class Unknown(val value: Int) : Toy
		}

		public sealed interface Peripheral : DeviceClass {
			public data object NonKeyboardNonPointing : Peripheral
			public data object Keyboard : Peripheral
			public data object Pointing : Peripheral
			public data object KeyboardPointing : Peripheral
			public data class Unknown(val value: Int) : Peripheral
		}
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
