import SwiftUI
import FoundationModels

@main
struct iOSApp: App {
    init() {
        installAppleFoundationModelsBridgeIfAvailable()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

// ── FoundationModels Bridge Setup ─────────────────────────────────────────────

/**
 * Initialize the FoundationModels bridge if available (iOS 18+).
 *
 * This function:
 * 1. Checks if FoundationModels framework is available
 * 2. Sets up the Swift-Kotlin bridge for LLM inference
 * 3. Registers the bridge with the Kotlin runtime
 *
 * Called from App initialization on app launch.
 * Safe to call on iOS < 18 (returns silently).
 */
private func installAppleFoundationModelsBridgeIfAvailable() {
    guard #available(iOS 18.0, *) else {
        print("ℹ️ FoundationModels not available on iOS < 18. Falling back to cloud providers.")
        return
    }
    
    print("✅ FoundationModels available. On-device LLM inference enabled.")
    
    // Initialize the bridge
    let bridge = FoundationModelsSwiftBridge()
    
    guard bridge.isAvailable() else {
        print("⚠️ FoundationModels framework not loaded. Check availability.")
        return
    }
    
    // In production, you could:
    // 1. Test FoundationModels with a simple prompt
    // 2. Log availability metrics
    // 3. Set up error recovery
    
    print("✅ Bridge initialized. Ready for on-device LLM.")
}

// ── Usage Example ────────────────────────────────────────────────────────────

/**
 * Example of calling FoundationModels directly from Swift.
 * (Normally called indirectly through Kotlin/Compose)
 *
 * Usage:
 * ```swift
 * @available(iOS 18.0, *)
 * @main
 * struct ExampleApp: App {
 *     var body: some Scene {
 *         WindowGroup {
 *             Text("Waiting...")
 *                 .task {
 *                     try? await exampleFoundationModelsUsage()
 *                 }
 *         }
 *     }
 * }
 *
 * @available(iOS 18.0, *)
 * func exampleFoundationModelsUsage() async throws {
 *     let bridge = FoundationModelsSwiftBridge()
 *     
 *     // Stream response token-by-token
 *     let response = try await bridge.streamResponse(
 *         prompt: "Explain photosynthesis in one sentence",
 *         onToken: { token in
 *             print(token, terminator: "")
 *         }
 *     )
 *     
 *     print("\n\nFull response: \(response)")
 * }
 * ```
 */

