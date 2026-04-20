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

	public sealed interface AddressType {
		public data object Public : AddressType
		public data object Random : AddressType
		public data object Anonymous : AddressType
		public data object Unknown : AddressType
		public data class UnknownValue(val value: Int?) : AddressType
	}

	public sealed interface DeviceClass {

		public data object Uncategorized : DeviceClass
		public data class UnknownValue(val value: Int?) : DeviceClass

		public sealed interface Computer : DeviceClass {
			public data object Uncategorized : Computer
			public data object Desktop : Computer
			public data object Server : Computer
			public data object Laptop : Computer
			public data object HandheldPcPda : Computer
			public data object PalmSizePcPda : Computer
			public data object Wearable : Computer
			public data class UnknownValue(val value: Int?) : Computer
		}

		public sealed interface Phone : DeviceClass {
			public data object Uncategorized : Phone
			public data object Cellular : Phone
			public data object Cordless : Phone
			public data object Smart : Phone
			public data object ModemOrGateway : Phone
			public data object Isdn : Phone
			public data class UnknownValue(val value: Int?) : Phone
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
			public data class UnknownValue(val value: Int?) : AudioVideo
		}

		public sealed interface Peripheral : DeviceClass {
			public data object NonKeyboardNonPointing : Peripheral
			public data object Keyboard : Peripheral
			public data object Pointing : Peripheral
			public data object KeyboardPointing : Peripheral

			public data object Joystick : Peripheral
			public data object Gamepad : Peripheral
			public data object RemoteControl : Peripheral
			public data object SensingDevice : Peripheral
			public data object DigitizerTablet : Peripheral
			public data object CardReader : Peripheral
			public data object DigitalPen : Peripheral
			public data object HandheldScanner : Peripheral
			public data object HandheldGesturalInput : Peripheral

			public data class UnknownValue(val value: Int?) : Peripheral
		}

		public sealed interface Imaging : DeviceClass {
			public data object Display : Imaging
			public data object Camera : Imaging
			public data object Scanner : Imaging
			public data object Printer : Imaging
			public data class UnknownValue(val value: Int?) : Imaging
		}

		public sealed interface Wearable : DeviceClass {
			public data object Uncategorized : Wearable
			public data object WristWatch : Wearable
			public data object Pager : Wearable
			public data object Jacket : Wearable
			public data object Helmet : Wearable
			public data object Glasses : Wearable
			public data object Pin : Wearable
			public data class UnknownValue(val value: Int?) : Wearable
		}

		public sealed interface Toy : DeviceClass {
			public data object Uncategorized : Toy
			public data object Robot : Toy
			public data object Vehicle : Toy
			public data object DollActionFigure : Toy
			public data object Controller : Toy
			public data object Game : Toy
			public data class UnknownValue(val value: Int?) : Toy
		}

		public sealed interface Health : DeviceClass {
			public data object Uncategorized : Health
			public data object BloodPressureMonitor : Health
			public data object Thermometer : Health
			public data object WeighingScale : Health
			public data object GlucoseMeter : Health
			public data object PulseOximeter : Health
			public data object HeartPulseRateMonitor : Health
			public data object DataDisplay : Health

			public data object StepCounter : Health
			public data object BodyCompositionAnalyzer : Health
			public data object PeakFlowMonitor : Health
			public data object MedicationMonitor : Health
			public data object KneeProsthesis : Health
			public data object AnkleProsthesis : Health
			public data object GenericHealthManager : Health
			public data object PersonaMobilityDevice : Health

			public data class UnknownValue(val value: Int?) : Health
		}
	}

	public sealed interface MajorDeviceClass {
		public data object Miscellaneous : MajorDeviceClass
		public data object Computer : MajorDeviceClass
		public data object Phone : MajorDeviceClass
		public data object Networking : MajorDeviceClass
		public data object AudioVideo : MajorDeviceClass
		public data object Peripheral : MajorDeviceClass
		public data object Imaging : MajorDeviceClass
		public data object Wearable : MajorDeviceClass
		public data object Toy : MajorDeviceClass
		public data object Health : MajorDeviceClass
		public data object Uncategorized : MajorDeviceClass
		public data class UnknownValue(val value: Int?) : MajorDeviceClass
	}

	public sealed interface Type {
		public data object Classic : Type
		public data object Le : Type
		public data object Dual : Type
		public data object Unknown : Type
		public data class UnknownValue(val value: Int?) : Type
	}


	// Device classes taken from the Bluetooth specification: https://www.bluetooth.com/wp-content/uploads/Files/Specification/Assigned_Numbers.pdf
	internal object ExtraDeviceClass {
		const val PERIPHERAL_JOYSTICK = 0x501
		const val PERIPHERAL_GAMEPAD = 0x502
		const val PERIPHERAL_REMOTE_CONTROL = 0x503
		const val PERIPHERAL_SENSING_DEVICE = 0x504
		const val PERIPHERAL_DIGITIZER_TABLET = 0x505
		const val PERIPHERAL_CARD_READER = 0x506
		const val PERIPHERAL_DIGITAL_PEN = 0x507
		const val PERIPHERAL_HANDHELD_SCANNER = 0x508
		const val PERIPHERAL_HANDHELD_GESTURAL_INPUT = 0x509

		const val IMAGING_DISPLAY = 0x601
		const val IMAGING_CAMERA = 0x602
		const val IMAGING_SCANNER = 0x604
		const val IMAGING_PRINTER = 0x608

		const val WEARABLE_PIN = 0x718

		const val HEALTH_STEP_COUNTER = 0x0920
		const val HEALTH_BODY_COMPOSITION_ANALYZER = 0x0924
		const val HEALTH_PEAK_FLOW_MONITOR = 0x0928
		const val HEALTH_MEDICATION_MONITOR = 0x092C
		const val HEALTH_KNEE_PROSTHESIS = 0x0930
		const val HEALTH_ANKLE_PROSTHESIS = 0x0934
		const val HEALTH_GENERIC_HEALTH_MANAGER = 0x0938
		const val HEALTH_PERSONAL_MOBILITY_DEVICE = 0x093C
	}
}
