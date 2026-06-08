package com.elianfabian.lapisbt_rpc.util

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

internal object LapisKeyExchange {

	private const val ALGORITHM = "EC"
	private const val CURVE = "secp256r1"

	fun generateKeyPair(): KeyPair {
		val kpg = KeyPairGenerator.getInstance(ALGORITHM)
		kpg.initialize(ECGenParameterSpec(CURVE))
		return kpg.generateKeyPair()
	}

	fun deriveSharedSecret(privateKey: PrivateKey, remotePublicKeyBytes: ByteArray): ByteArray {
		val keyFactory = KeyFactory.getInstance(ALGORITHM)
		val publicKeySpec = X509EncodedKeySpec(remotePublicKeyBytes)
		val remotePublicKey = keyFactory.generatePublic(publicKeySpec)

		val keyAgreement = KeyAgreement.getInstance("ECDH")
		keyAgreement.init(privateKey)
		keyAgreement.doPhase(remotePublicKey, true)

		return keyAgreement.generateSecret()
	}
}
