package com.elianfabian.lapisbt_rpc.annotation

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
public annotation class LapisMethod(
	val name: String,
	val metadata: Array<Metadata> = [],
) {
	/**
	 * Custom configuration entries for the [com.elianfabian.lapisbt_rpc.LapisPacketProcessor].
	 *
	 * Metadata allows you to pass "out-of-band" instructions to the transport layer.
	 *
	 * This only works for messages of type request or response.
	 *
	 * **Example:**
	 * If your processor usually compresses all packets, you can define a "compression"
	 * metadata entry to disable it for time-sensitive or already-compressed data:
	 * ```
	 * @LapisMethod(
	 *     name = "uploadImage",
	 *     metadata = [LapisMethod.Metadata(key = "compress", value = "false")]
	 * )
	 * ```
	 */
	public annotation class Metadata(
		val key: String,
		val value: String,
	)
}
