import SwiftUI
import AVFoundation

@main
struct iOSApp: App {
    init() {
        // Povoleni prehravani zvuku na pozadi i pri zamknutem iPhonu
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            print("Chyba inicializace AVAudioSession: \(error)")
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
