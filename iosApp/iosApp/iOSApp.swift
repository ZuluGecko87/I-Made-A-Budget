import SwiftUI
import ComposeApp

@main
struct IMABApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                // The shared screens handle the safe area and keyboard themselves.
                .ignoresSafeArea()
        }
    }
}

/** The shared Compose screens. */
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController(services: IosNativeServices())
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
