[![](https://jitpack.io/v/ElianFabian/LapisBt.svg)](https://jitpack.io/#ElianFabian/LapisBt)

# LapisBt: Bluetooth Classic & RPC for Android

LapisBt is a high-level, "sanitized" abstraction for Bluetooth Classic on Android. It acts as a synchronization layer that internally validates connection health and bonding states to ensure that the provided states and event streams represent actual hardware reality, not just the stack's reported (and often inconsistent) state.

On top of this robust foundation, LapisBt provides a type-safe Remote Procedure Call (RPC) layer that allows for high-level communication between devices using standard Kotlin interfaces, abstracting away the complexities of byte buffers and manual serialization.

## Production Status

> [!WARNING]
> **Early Development Stage:** This library is currently in its early stages of development. While it aims to provide a more reliable Bluetooth experience, it may still contain bugs or undergo breaking API changes. It is **not yet recommended for critical production environments**. Feedback and contributions are highly welcome!

## Modules

- **`lapisbt`**: The core Bluetooth Classic synchronization layer.
- **`lapisbt-rpc`**: The type-safe RPC layer built on top of `lapisbt`.
- **`lapisbt-logger`**: Simple logging abstraction used throughout the library.

## Installation

Add it in your root `build.gradle.kts` at the end of repositories:

```kotlin
allprojects {
    repositories {
        ...
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependencies in your module's `build.gradle.kts`:

```kotlin
dependencies {
    // Core Bluetooth synchronization layer
    implementation("com.github.elianfabian:LapisBt:lapisbt:1.0.0")
    
    // Type-safe RPC layer (includes lapisbt)
    implementation("com.github.elianfabian:LapisBt:lapisbt-rpc:1.0.0")
}
```

## Core Concepts

### `LapisBt`

Standard Android Bluetooth APIs often emit inconsistent states or unreliable lifecycle broadcasts. `LapisBt` solves this by:
- **Validating Connection Health:** Ensures that "connected" actually means the hardware is ready.
- **Bonding Synchronization:** Handles the edge cases of pairing and unpairing to keep the local state in sync with the remote device.
- **Flow-based APIs:** Provides `StateFlow` for state, scanned devices, and connected devices, and `SharedFlow` for real-time events.

### `LapisBtRpc`

The RPC layer allows you to define a Kotlin interface and call its methods on a remote device as if they were local.
- **Type-Safety:** No more manual byte manipulation.
- **Flow Support:** Supports `Flow<T>` for data streaming (both as return types and parameters).
- **Annotations:** Use `@LapisRpc`, `@LapisMethod`, and `@LapisParam` to define your contract.
- **Encryption:** Built-in support for AES-GCM and automatic key exchange (ECDH).

## Usage

### 1. Define your RPC Interface

```kotlin
@LapisRpc("MyService")
interface MyService {

    @LapisMethod("greet")
    suspend fun greet(
      @LapisParam("name")
      name: String
	): String

    @LapisMethod("sensorData")
    fun sensorData(): Flow<Float>
}
```

### 2. Implement and Register the Server

```kotlin
class MyServiceServer : MyService {
    override suspend fun greet(name: String) = "Hello, $name!"
    
    override fun sensorData(): Flow<Float> = flow {
        // Emit sensor readings...
    }
}

// In your Activity/Service:
val lapisBt = LapisBt.newInstance(context)
val lapisBtRpc = LapisBtRpc.newInstance(lapisBt)

lapisBtRpc.registerBluetoothServerService<MyService>(
    deviceAddress = remoteDeviceAddress,
    server = MyServiceServer(),
)
```

### 3. Call from the Client

```kotlin
val service = lapisBtRpc.getOrCreateBluetoothClientService<MyService>(remoteDeviceAddress)

val greeting = service.greet("Lapis")
service.sensorData().collect { value ->
    println("Received: $value")
}
```

## Advanced Features

- **Simulated Environment:** Test your Bluetooth logic using `LapisBt.newSimulatedBluetoothEnvironment()`.
- **Custom Serialization:** Implement `LapisSerializationStrategy` to support custom types or formats like JSON/Protobuf.
- **Encryption:**
  ```kotlin
  lapisBtRpc.setEncryption(deviceAddress, LapisEncryption.aesGcm(myKey))
  // Or use automatic ECDH key exchange:
  lapisBtRpc.setEncryption(deviceAddress, LapisEncryption.automatic())
  ```

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
