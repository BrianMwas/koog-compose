import FoundationModels
import ComposeApp

@available(iOS 26.0, *)
final class AppleFoundationModelsBridgeImpl: NSObject, AppleFoundationModelsBridge {
    func isAvailable() -> Bool {
        SystemLanguageModel.default.isAvailable
    }

    func unavailableReason() -> String? {
        switch SystemLanguageModel.default.availability {
        case .available:
            return nil
        case .unavailable(let reason):
            switch reason {
            case .deviceNotEligible:
                return "This device is not eligible for Apple Intelligence."
            case .appleIntelligenceNotEnabled:
                return "Apple Intelligence is not enabled."
            case .modelNotReady:
                return "The on-device model is not ready yet."
            @unknown default:
                return "The on-device model is unavailable."
            }
        @unknown default:
            return "The on-device model is unavailable."
        }
    }

    func supportsToolCalls() -> Bool {
        false
    }

    func execute(
        systemPrompt: String?,
        userPrompt: String,
        onComplete: @escaping (String?, String?) -> Void
    ) {
        Task {
            do {
                let session = makeSession(systemPrompt: systemPrompt)
                let response = try await session.respond(to: userPrompt)
                onComplete(response.content, nil)
            } catch {
                onComplete(nil, String(describing: error))
            }
        }
    }

    func stream(
        systemPrompt: String?,
        userPrompt: String,
        onToken: @escaping (String) -> Void,
        onComplete: @escaping (String?) -> Void
    ) {
        Task {
            do {
                let session = makeSession(systemPrompt: systemPrompt)
                var emitted = ""

                for try await snapshot in session.streamResponse(to: userPrompt) {
                    let full = String(describing: snapshot.content)
                    let nextChunk: String

                    if full.hasPrefix(emitted) {
                        nextChunk = String(full.dropFirst(emitted.count))
                    } else {
                        nextChunk = full
                    }

                    if !nextChunk.isEmpty {
                        onToken(nextChunk)
                    }
                    emitted = full
                }

                onComplete(nil)
            } catch {
                onComplete(String(describing: error))
            }
        }
    }

    private func makeSession(systemPrompt: String?) -> LanguageModelSession {
        if let systemPrompt, !systemPrompt.isEmpty {
            return LanguageModelSession(instructions: systemPrompt)
        } else {
            return LanguageModelSession()
        }
    }
}

func installAppleFoundationModelsBridgeIfAvailable() {
    OnDeviceAIProviderKt.installOnDeviceProviderSupport()

    if #available(iOS 26.0, *) {
        AppleFoundationModelsBridgeSupportKt.installAppleFoundationModelsBridge(
            bridge: AppleFoundationModelsBridgeImpl()
        )
    }
}
