package io.github.koogcompose.sample

import platform.UIKit.UIDevice

/**
 * iOS-specific platform detection for version checking.
 * Used to determine if FoundationModels API is available (iOS 18+).
 */
internal actual object Platform {
    actual val osVersion: String
        get() = UIDevice.currentDevice.systemVersion
}
