# 1. Essential Attributes for Reflection, Generics, and RPC
# Signature: Required for Flow<T> and generic return types.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature

# 2. Keep the LapisBt Annotation Classes
# This ensures the library can find its own annotations at runtime.
-keep class com.elianfabian.lapisbt_rpc.annotation.** { *; }

# 3. Keep RPC Service Definitions
# This ensures that any interface marked with @LapisRpc is not renamed,
# and all methods inside it (regardless of @LapisMethod) are kept to prevent missing members.
-keep @com.elianfabian.lapisbt_rpc.annotation.LapisRpc interface * {
    <methods>;
}

# 4. Keep Kotlin/Coroutine Internals for Flow and Suspend Support
# Without these, your RPC engine will fail to recognize 'suspend' returns (Continuation)
# or 'Flow' types after they are obfuscated.
-keep interface kotlinx.coroutines.flow.Flow { *; }
-keep interface kotlin.coroutines.Continuation { *; }
