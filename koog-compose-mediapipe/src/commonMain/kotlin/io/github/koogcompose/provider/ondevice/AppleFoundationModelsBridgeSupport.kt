package io.github.koogcompose.provider.ondevice

/**
 * ObjC/Swift-friendly bridge contract for Apple Foundation Models.
 *
 * The implementation lives on the Swift side because the system framework is
 * Apple-platform-native and evolves independently of Kotlin/Native exports.
 */
public interface AppleFoundationModelsBridge {
    public fun isAvailable(): Boolean
    public fun unavailableReason(): String?
    public fun supportsToolCalls(): Boolean
    public fun execute(
        systemPrompt: String?,
        userPrompt: String,
        onComplete: (result: String?, error: String?) -> Unit,
    )
    public fun stream(
        systemPrompt: String?,
        userPrompt: String,
        onToken: (String) -> Unit,
        onComplete: (error: String?) -> Unit,
    )
}

public object AppleFoundationModelsBridgeRegistry {
    public var bridge: AppleFoundationModelsBridge? = null
}

public fun installAppleFoundationModelsBridge(bridge: AppleFoundationModelsBridge) {
    AppleFoundationModelsBridgeRegistry.bridge = bridge
}

public fun clearAppleFoundationModelsBridge() {
    AppleFoundationModelsBridgeRegistry.bridge = null
}

public fun installOnDeviceBridges(bridge: AppleFoundationModelsBridge? = null) {
    installOnDeviceProviderSupport()
    if (bridge != null) {
        installAppleFoundationModelsBridge(bridge)
    }
}
