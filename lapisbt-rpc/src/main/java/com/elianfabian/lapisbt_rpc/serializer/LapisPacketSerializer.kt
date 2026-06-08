package com.elianfabian.lapisbt_rpc.serializer

import com.elianfabian.lapisbt_rpc.model.CompleteBluetoothPacket
import com.elianfabian.lapisbt_rpc.model.LapisRpcPacket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

internal object LapisPacketSerializer {

	fun serialize(stream: OutputStream, packet: LapisRpcPacket) {
		val output = DataOutputStream(stream)
		output.writeInt(packet.requestId)

		when (packet) {
			is LapisRpcPacket.Request -> {
				output.writeUTF(packet.serviceName)
				output.writeUTF(packet.methodName)

				output.writeInt(packet.rawArguments.size)
				for ((key, value) in packet.rawArguments) {
					output.writeUTF(key)
					output.writeByteArray(value)
				}

				output.writeByteArray(packet.rawMetadata)
			}
			is LapisRpcPacket.Response -> {
				output.writeByteArray(packet.rawData)
			}
			is LapisRpcPacket.ErrorResponse -> {
				output.writeUTF(packet.message)
			}
			is LapisRpcPacket.Handshake -> {
				output.writeByteArray(packet.publicKey)
			}
			is LapisRpcPacket.FlowParameter -> {
				output.writeInt(packet.flowId)
				output.writeUTF(packet.parameterName)

				when (packet) {
					is LapisRpcPacket.FlowParameter.Emission -> {
						output.writeByteArray(packet.rawData)
					}
					is LapisRpcPacket.FlowParameter.Error -> {
						output.writeUTF(packet.message)
					}
					is LapisRpcPacket.FlowParameter.Collection,
					is LapisRpcPacket.FlowParameter.Completion,
					is LapisRpcPacket.FlowParameter.Cancellation,
						-> Unit
				}
			}
			is LapisRpcPacket.Cancellation,
			is LapisRpcPacket.Completion,
				-> Unit
		}
	}

	fun deserialize(type: CompleteBluetoothPacket.Type, stream: InputStream): LapisRpcPacket {
		val input = DataInputStream(stream)
		val requestId = input.readInt()

		return when (type) {
			CompleteBluetoothPacket.Type.Request -> {
				val serviceName = input.readUTF()
				val methodName = input.readUTF()
				val argsSize = input.readInt()

				val args = mutableMapOf<String, ByteArray>()
				repeat(argsSize) {
					args[input.readUTF()] = input.readByteArray()
				}

				LapisRpcPacket.Request(
					requestId = requestId,
					serviceName = serviceName,
					methodName = methodName,
					rawArguments = args,
					rawMetadata = input.readByteArray(),
				)
			}
			CompleteBluetoothPacket.Type.Response -> {
				LapisRpcPacket.Response(
					requestId = requestId,
					rawData = input.readByteArray(),
				)
			}
			CompleteBluetoothPacket.Type.ErrorResponse -> {
				LapisRpcPacket.ErrorResponse(
					requestId = requestId,
					message = input.readUTF(),
				)
			}
			CompleteBluetoothPacket.Type.Cancellation -> {
				LapisRpcPacket.Cancellation(requestId)
			}
			CompleteBluetoothPacket.Type.Completion -> {
				LapisRpcPacket.Completion(requestId)
			}
			CompleteBluetoothPacket.Type.Handshake -> {
				LapisRpcPacket.Handshake(
					requestId = requestId,
					publicKey = input.readByteArray()
				)
			}
			CompleteBluetoothPacket.Type.FlowParameterCollection -> {
				LapisRpcPacket.FlowParameter.Collection(
					requestId = requestId,
					flowId = input.readInt(),
					parameterName = input.readUTF(),
				)
			}
			CompleteBluetoothPacket.Type.FlowParameterEmission -> {
				LapisRpcPacket.FlowParameter.Emission(
					requestId = requestId,
					flowId = input.readInt(),
					parameterName = input.readUTF(),
					rawData = input.readByteArray(),
				)
			}
			CompleteBluetoothPacket.Type.FlowParameterCompletion -> {
				LapisRpcPacket.FlowParameter.Completion(
					requestId = requestId,
					flowId = input.readInt(),
					parameterName = input.readUTF(),
				)
			}
			CompleteBluetoothPacket.Type.FlowParameterCancellation -> {
				LapisRpcPacket.FlowParameter.Cancellation(
					requestId = requestId,
					flowId = input.readInt(),
					parameterName = input.readUTF(),
				)
			}
			CompleteBluetoothPacket.Type.FlowParameterError -> {
				LapisRpcPacket.FlowParameter.Error(
					requestId = requestId,
					flowId = input.readInt(),
					parameterName = input.readUTF(),
					message = input.readUTF(),
				)
			}
		}
	}


	private fun DataOutputStream.writeByteArray(bytes: ByteArray) {
		writeInt(bytes.size)
		write(bytes)
	}

	private fun DataInputStream.readByteArray(): ByteArray {
		val size = readInt()
		val bytes = ByteArray(size)
		readFully(bytes)
		return bytes
	}
}
