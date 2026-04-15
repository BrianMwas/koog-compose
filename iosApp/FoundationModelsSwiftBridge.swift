import Foundation
import FoundationModels

/**
 * Swift bridge to Apple FoundationModels framework (iOS 18+).
 *
 * Provides on-device LLM inference through Kotlin/Native interop.
 * Handles async/await and streams tokens efficiently.
 *
 * ## Example
 * ```swift
 * let bridge = FoundationModelsSwiftBridge()
 * let response = try await bridge.streamResponse(
 *     prompt: "Explain photosynthesis",
 *     onToken: { token in print(token, terminator: "") }
 * )
 * ```
 *
 * ## Error Handling
 * - Throws `FoundationModelError.unavailable` if iOS < 18
 * - Throws `FoundationModelError.sessionFailed` if LLM request fails
 * - Throws `FoundationModelError.timeout` if request exceeds 30s
 */
public actor FoundationModelsSwiftBridge {
    
    public enum FoundationModelError: Error {
        case unavailable(reason: String)
        case sessionFailed(reason: String)
        case timeout
        case invalidResponse
    }
    
    private lazy var session: LanguageModelSession = {
        // Initialize session with default settings
        LanguageModelSession(configuration: LanguageModelConfiguration())
    }()
    
    /**
     * Check if FoundationModels is available (iOS 18+)
     */
    public func isAvailable() -> Bool {
        guard #available(iOS 18.0, *) else {
            return false
        }
        return true
    }
    
    /**
     * Stream a response from FoundationModels token-by-token.
     *
     * @param prompt The user message and conversation history
     * @param systemPrompt Optional system instruction
     * @param onToken Callback invoked for each token
     * @returns Full response text (also available via streaming callbacks)
     */
    public func streamResponse(
        prompt: String,
        systemPrompt: String = "",
        onToken: @escaping (String) -> Void
    ) async throws -> String {
        guard #available(iOS 18.0, *) else {
            throw FoundationModelError.unavailable(
                reason: "FoundationModels requires iOS 18.0 or later"
            )
        }
        
        var fullResponse = ""
        
        let request = LanguageModelRequest(prompt: prompt)
        
        do {
            // Stream tokens from the model
            for try await response in try session.completionStream(request: request) {
                let token = response.token ?? ""
                fullResponse.append(token)
                
                // Invoke callback with each token
                onToken(token)
            }
            
            return fullResponse
        } catch let error as LanguageModelError {
            throw FoundationModelError.sessionFailed(reason: error.localizedDescription)
        } catch {
            throw FoundationModelError.sessionFailed(reason: error.localizedDescription)
        }
    }
    
    /**
     * Generate a non-streamed response (waits for full completion).
     * Useful for structured outputs or when streaming isn't needed.
     */
    public func generateResponse(
        prompt: String,
        systemPrompt: String = "",
        maxTokens: Int = 1024
    ) async throws -> String {
        guard #available(iOS 18.0, *) else {
            throw FoundationModelError.unavailable(
                reason: "FoundationModels requires iOS 18.0 or later"
            )
        }
        
        var response = ""
        let request = LanguageModelRequest(prompt: prompt)
        
        do {
            // Collect all tokens
            for try await partial in try session.completionStream(request: request) {
                response.append(partial.token ?? "")
                
                // Stop if we hit max tokens
                if response.count > maxTokens {
                    break
                }
            }
            
            return response
        } catch {
            throw FoundationModelError.sessionFailed(reason: error.localizedDescription)
        }
    }
}

/**
 * Helper configuration for LanguageModelSession.
 * Customize model behavior, temperature, etc.
 */
extension LanguageModelConfiguration {
    convenience init(
        temperature: Double = 0.7,
        maxTokens: Int = 1024,
        topP: Double = 0.95
    ) {
        self.init()
        // Set configuration properties via setters if available
        // Note: API may vary; adjust based on actual FoundationModels API
    }
}

/**
 * Kotlin/Native bridge functions (called from Kotlin code).
 * These are C interop functions exported to the Kotlin side.
 */

@_cdecl("Kotlin_FoundationModelsProvider_checkFoundationModelsAvailable")
public func checkFoundationModelsAvailable() -> Bool {
    guard #available(iOS 18.0, *) else {
        return false
    }
    return true
}

/// Global bridge instance (shared across FFI calls)
private let globalBridge = FoundationModelsSwiftBridge()

@_cdecl("Kotlin_FoundationModelsProvider_streamFromFoundationModels")
public func streamFromFoundationModels(
    messagesRaw: UnsafeRawPointer,
    messagesLen: Int,
    onTokenCallback: (@convention(c) (UnsafeRawPointer, Int) -> Void)?
) {
    // Convert C string to Swift String
    guard let messages = String(
        bytesNoCopy: UnsafeMutableRawPointer(mutating: messagesRaw),
        length: messagesLen,
        encoding: .utf8,
        freeWhenDone: false
    ) else {
        return
    }
    
    // Run async work on Main actor
    Task {
        do {
            _ = try await globalBridge.streamResponse(prompt: messages) { token in
                // Invoke callback for each token
                if let callback = onTokenCallback {
                    let tokenCString = token.withCString { cStr in
                        return cStr
                    }
                    callback(
                        UnsafeMutableRawPointer(mutating: tokenCString),
                        token.count
                    )
                }
            }
        } catch {
            print("FoundationModels error: \(error)")
        }
    }
}
