package com.elianfabian.lapisbt_rpc.annotation

/**
 * Marker for custom method metadata annotations.
 * Any annotation marked with this will be processed by the [com.elianfabian.lapisbt_rpc.LapisPacketProcessor].
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
public annotation class LapisMetadata
