import SwiftUI

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
