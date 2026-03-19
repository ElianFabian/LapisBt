package com.elianfabian.lapisbt.util

import android.bluetooth.BluetoothClass
import com.elianfabian.lapisbt.abstraction.LapisBluetoothDevice
import com.elianfabian.lapisbt.model.BluetoothDevice

internal fun LapisBluetoothDevice.toModel(connectionState: BluetoothDevice.ConnectionState): BluetoothDevice {
	return BluetoothDevice(
		address = this.address,
		name = this.name,
		alias = this.alias,
		addressType = when (this.addressType) {
			AndroidBluetoothDevice.ADDRESS_TYPE_PUBLIC -> BluetoothDevice.AddressType.Public
			AndroidBluetoothDevice.ADDRESS_TYPE_RANDOM -> BluetoothDevice.AddressType.Random
			AndroidBluetoothDevice.ADDRESS_TYPE_ANONYMOUS -> BluetoothDevice.AddressType.Anonymous
			AndroidBluetoothDevice.ADDRESS_TYPE_UNKNOWN -> BluetoothDevice.AddressType.Unknown
			else -> BluetoothDevice.AddressType.UnknownValue(this.addressType)
		},
		deviceClass = when (this.deviceClass) {
			BluetoothClass.Device.COMPUTER_UNCATEGORIZED -> BluetoothDevice.DeviceClass.Computer.Uncategorized
			BluetoothClass.Device.COMPUTER_DESKTOP -> BluetoothDevice.DeviceClass.Computer.Desktop
			BluetoothClass.Device.COMPUTER_SERVER -> BluetoothDevice.DeviceClass.Computer.Server
			BluetoothClass.Device.COMPUTER_LAPTOP -> BluetoothDevice.DeviceClass.Computer.Laptop
			BluetoothClass.Device.COMPUTER_HANDHELD_PC_PDA -> BluetoothDevice.DeviceClass.Computer.HandheldPcPda
			BluetoothClass.Device.COMPUTER_PALM_SIZE_PC_PDA -> BluetoothDevice.DeviceClass.Computer.PalmSizePcPda
			BluetoothClass.Device.COMPUTER_WEARABLE -> BluetoothDevice.DeviceClass.Computer.Wearable

			BluetoothClass.Device.PHONE_UNCATEGORIZED -> BluetoothDevice.DeviceClass.Phone.Uncategorized
			BluetoothClass.Device.PHONE_CELLULAR -> BluetoothDevice.DeviceClass.Phone.Cellular
			BluetoothClass.Device.PHONE_CORDLESS -> BluetoothDevice.DeviceClass.Phone.Cordless
			BluetoothClass.Device.PHONE_SMART -> BluetoothDevice.DeviceClass.Phone.Smart
			BluetoothClass.Device.PHONE_MODEM_OR_GATEWAY -> BluetoothDevice.DeviceClass.Phone.ModemOrGateway
			BluetoothClass.Device.PHONE_ISDN -> BluetoothDevice.DeviceClass.Phone.Isdn

			BluetoothClass.Device.AUDIO_VIDEO_UNCATEGORIZED -> BluetoothDevice.DeviceClass.AudioVideo.Uncategorized
			BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET -> BluetoothDevice.DeviceClass.AudioVideo.WearableHeadset
			BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> BluetoothDevice.DeviceClass.AudioVideo.Handsfree
			BluetoothClass.Device.AUDIO_VIDEO_MICROPHONE -> BluetoothDevice.DeviceClass.AudioVideo.Microphone
			BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER -> BluetoothDevice.DeviceClass.AudioVideo.Loudspeaker
			BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES -> BluetoothDevice.DeviceClass.AudioVideo.Headphones
			BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO -> BluetoothDevice.DeviceClass.AudioVideo.PortableAudio
			BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> BluetoothDevice.DeviceClass.AudioVideo.CarAudio
			BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX -> BluetoothDevice.DeviceClass.AudioVideo.SetTopBox
			BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> BluetoothDevice.DeviceClass.AudioVideo.HifiAudio
			BluetoothClass.Device.AUDIO_VIDEO_VCR -> BluetoothDevice.DeviceClass.AudioVideo.Vcr
			BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CAMERA -> BluetoothDevice.DeviceClass.AudioVideo.VideoCamera
			BluetoothClass.Device.AUDIO_VIDEO_CAMCORDER -> BluetoothDevice.DeviceClass.AudioVideo.Camcorder
			BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR -> BluetoothDevice.DeviceClass.AudioVideo.VideoMonitor
			BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER -> BluetoothDevice.DeviceClass.AudioVideo.VideoDisplayAndLoudspeaker
			BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CONFERENCING -> BluetoothDevice.DeviceClass.AudioVideo.VideoConferencing
			BluetoothClass.Device.AUDIO_VIDEO_VIDEO_GAMING_TOY -> BluetoothDevice.DeviceClass.AudioVideo.VideoGamingToy

			BluetoothClass.Device.PERIPHERAL_NON_KEYBOARD_NON_POINTING -> BluetoothDevice.DeviceClass.Peripheral.NonKeyboardNonPointing
			BluetoothClass.Device.PERIPHERAL_KEYBOARD -> BluetoothDevice.DeviceClass.Peripheral.Keyboard
			BluetoothClass.Device.PERIPHERAL_POINTING -> BluetoothDevice.DeviceClass.Peripheral.Pointing
			BluetoothClass.Device.PERIPHERAL_KEYBOARD_POINTING -> BluetoothDevice.DeviceClass.Peripheral.KeyboardPointing

			BluetoothDevice.ExtraDeviceClass.PERIPHERAL_JOYSTICK -> BluetoothDevice.DeviceClass.Peripheral.Joystick
			BluetoothDevice.ExtraDeviceClass.PERIPHERAL_GAMEPAD -> BluetoothDevice.DeviceClass.Peripheral.Gamepad
			BluetoothDevice.ExtraDeviceClass.PERIPHERAL_REMOTE_CONTROL -> BluetoothDevice.DeviceClass.Peripheral.RemoteControl
			BluetoothDevice.ExtraDeviceClass.PERIPHERAL_SENSING_DEVICE -> BluetoothDevice.DeviceClass.Peripheral.SensingDevice
			BluetoothDevice.ExtraDeviceClass.PERIPHERAL_DIGITIZER_TABLET -> BluetoothDevice.DeviceClass.Peripheral.DigitizerTablet
			BluetoothDevice.ExtraDeviceClass.PERIPHERAL_CARD_READER -> BluetoothDevice.DeviceClass.Peripheral.CardReader
			BluetoothDevice.ExtraDeviceClass.PERIPHERAL_DIGITAL_PEN -> BluetoothDevice.DeviceClass.Peripheral.DigitalPen
			BluetoothDevice.ExtraDeviceClass.PERIPHERAL_HANDHELD_SCANNER -> BluetoothDevice.DeviceClass.Peripheral.HandheldScanner
			BluetoothDevice.ExtraDeviceClass.PERIPHERAL_HANDHELD_GESTURAL_INPUT -> BluetoothDevice.DeviceClass.Peripheral.HandheldGesturalInput

			BluetoothDevice.ExtraDeviceClass.IMAGING_DISPLAY -> BluetoothDevice.DeviceClass.Imaging.Display
			BluetoothDevice.ExtraDeviceClass.IMAGING_CAMERA -> BluetoothDevice.DeviceClass.Imaging.Camera
			BluetoothDevice.ExtraDeviceClass.IMAGING_SCANNER -> BluetoothDevice.DeviceClass.Imaging.Scanner
			BluetoothDevice.ExtraDeviceClass.IMAGING_PRINTER -> BluetoothDevice.DeviceClass.Imaging.Printer

			BluetoothClass.Device.WEARABLE_UNCATEGORIZED -> BluetoothDevice.DeviceClass.Wearable.Uncategorized
			BluetoothClass.Device.WEARABLE_WRIST_WATCH -> BluetoothDevice.DeviceClass.Wearable.WristWatch
			BluetoothClass.Device.WEARABLE_PAGER -> BluetoothDevice.DeviceClass.Wearable.Pager
			BluetoothClass.Device.WEARABLE_JACKET -> BluetoothDevice.DeviceClass.Wearable.Jacket
			BluetoothClass.Device.WEARABLE_HELMET -> BluetoothDevice.DeviceClass.Wearable.Helmet
			BluetoothClass.Device.WEARABLE_GLASSES -> BluetoothDevice.DeviceClass.Wearable.Glasses

			BluetoothClass.Device.TOY_UNCATEGORIZED -> BluetoothDevice.DeviceClass.Toy.Uncategorized
			BluetoothClass.Device.TOY_ROBOT -> BluetoothDevice.DeviceClass.Toy.Robot
			BluetoothClass.Device.TOY_VEHICLE -> BluetoothDevice.DeviceClass.Toy.Vehicle
			BluetoothClass.Device.TOY_DOLL_ACTION_FIGURE -> BluetoothDevice.DeviceClass.Toy.DollActionFigure
			BluetoothClass.Device.TOY_CONTROLLER -> BluetoothDevice.DeviceClass.Toy.Controller
			BluetoothClass.Device.TOY_GAME -> BluetoothDevice.DeviceClass.Toy.Game

			BluetoothClass.Device.HEALTH_UNCATEGORIZED -> BluetoothDevice.DeviceClass.Health.Uncategorized
			BluetoothClass.Device.HEALTH_BLOOD_PRESSURE -> BluetoothDevice.DeviceClass.Health.BloodPressureMonitor
			BluetoothClass.Device.HEALTH_THERMOMETER -> BluetoothDevice.DeviceClass.Health.Thermometer
			BluetoothClass.Device.HEALTH_WEIGHING -> BluetoothDevice.DeviceClass.Health.WeighingScale
			BluetoothClass.Device.HEALTH_GLUCOSE -> BluetoothDevice.DeviceClass.Health.GlucoseMeter
			BluetoothClass.Device.HEALTH_PULSE_OXIMETER -> BluetoothDevice.DeviceClass.Health.PulseOximeter
			BluetoothClass.Device.HEALTH_PULSE_RATE -> BluetoothDevice.DeviceClass.Health.HeartPulseRateMonitor
			BluetoothClass.Device.HEALTH_DATA_DISPLAY -> BluetoothDevice.DeviceClass.Health.DataDisplay

			BluetoothDevice.ExtraDeviceClass.HEALTH_STEP_COUNTER -> BluetoothDevice.DeviceClass.Health.StepCounter
			BluetoothDevice.ExtraDeviceClass.HEALTH_BODY_COMPOSITION_ANALYZER -> BluetoothDevice.DeviceClass.Health.BodyCompositionAnalyzer
			BluetoothDevice.ExtraDeviceClass.HEALTH_PEAK_FLOW_MONITOR -> BluetoothDevice.DeviceClass.Health.PeakFlowMonitor
			BluetoothDevice.ExtraDeviceClass.HEALTH_MEDICATION_MONITOR -> BluetoothDevice.DeviceClass.Health.MedicationMonitor
			BluetoothDevice.ExtraDeviceClass.HEALTH_KNEE_PROSTHESIS -> BluetoothDevice.DeviceClass.Health.KneeProsthesis
			BluetoothDevice.ExtraDeviceClass.HEALTH_ANKLE_PROSTHESIS -> BluetoothDevice.DeviceClass.Health.AnkleProsthesis
			BluetoothDevice.ExtraDeviceClass.HEALTH_GENERIC_HEALTH_MANAGER -> BluetoothDevice.DeviceClass.Health.GenericHealthManager
			BluetoothDevice.ExtraDeviceClass.HEALTH_PERSONAL_MOBILITY_DEVICE -> BluetoothDevice.DeviceClass.Health.PersonaMobilityDevice

			else -> when (this.majorDeviceClass) {
				BluetoothClass.Device.Major.UNCATEGORIZED -> BluetoothDevice.DeviceClass.Uncategorized
				BluetoothClass.Device.Major.COMPUTER -> BluetoothDevice.DeviceClass.Computer.UnknownValue(this.deviceClass)
				BluetoothClass.Device.Major.PHONE -> BluetoothDevice.DeviceClass.Phone.UnknownValue(this.deviceClass)
				BluetoothClass.Device.Major.AUDIO_VIDEO -> BluetoothDevice.DeviceClass.AudioVideo.UnknownValue(this.deviceClass)
				BluetoothClass.Device.Major.PERIPHERAL -> BluetoothDevice.DeviceClass.Peripheral.UnknownValue(this.deviceClass)
				BluetoothClass.Device.Major.IMAGING -> BluetoothDevice.DeviceClass.Imaging.UnknownValue(this.deviceClass)
				BluetoothClass.Device.Major.WEARABLE -> BluetoothDevice.DeviceClass.Wearable.UnknownValue(this.deviceClass)
				BluetoothClass.Device.Major.TOY -> BluetoothDevice.DeviceClass.Toy.UnknownValue(this.deviceClass)
				BluetoothClass.Device.Major.HEALTH -> BluetoothDevice.DeviceClass.Health.UnknownValue(this.deviceClass)
				else -> BluetoothDevice.DeviceClass.UnknownValue(this.deviceClass)
			}
		},
		majorDeviceClass = when (this.majorDeviceClass) {
			BluetoothClass.Device.Major.MISC -> BluetoothDevice.MajorDeviceClass.Miscellaneous
			BluetoothClass.Device.Major.COMPUTER -> BluetoothDevice.MajorDeviceClass.Computer
			BluetoothClass.Device.Major.PHONE -> BluetoothDevice.MajorDeviceClass.Phone
			BluetoothClass.Device.Major.NETWORKING -> BluetoothDevice.MajorDeviceClass.Networking
			BluetoothClass.Device.Major.AUDIO_VIDEO -> BluetoothDevice.MajorDeviceClass.AudioVideo
			BluetoothClass.Device.Major.PERIPHERAL -> BluetoothDevice.MajorDeviceClass.Peripheral
			BluetoothClass.Device.Major.IMAGING -> BluetoothDevice.MajorDeviceClass.Imaging
			BluetoothClass.Device.Major.WEARABLE -> BluetoothDevice.MajorDeviceClass.Wearable
			BluetoothClass.Device.Major.TOY -> BluetoothDevice.MajorDeviceClass.Toy
			BluetoothClass.Device.Major.HEALTH -> BluetoothDevice.MajorDeviceClass.Health
			BluetoothClass.Device.Major.UNCATEGORIZED -> BluetoothDevice.MajorDeviceClass.Uncategorized
			else -> BluetoothDevice.MajorDeviceClass.UnknownValue(this.majorDeviceClass)
		},
		type = when (this.type) {
			AndroidBluetoothDevice.DEVICE_TYPE_CLASSIC -> BluetoothDevice.Type.Classic
			AndroidBluetoothDevice.DEVICE_TYPE_LE -> BluetoothDevice.Type.Le
			AndroidBluetoothDevice.DEVICE_TYPE_DUAL -> BluetoothDevice.Type.Dual
			AndroidBluetoothDevice.DEVICE_TYPE_UNKNOWN -> BluetoothDevice.Type.Unknown
			else -> BluetoothDevice.Type.UnknownValue(this.type)
		},
		uuids = this.uuids,
		pairingState = when (this.bondState) {
			AndroidBluetoothDevice.BOND_BONDED -> BluetoothDevice.PairingState.Paired
			AndroidBluetoothDevice.BOND_BONDING -> BluetoothDevice.PairingState.Pairing
			AndroidBluetoothDevice.BOND_NONE -> BluetoothDevice.PairingState.None
			else -> BluetoothDevice.PairingState.None
		},
		connectionState = connectionState,
	)
}
