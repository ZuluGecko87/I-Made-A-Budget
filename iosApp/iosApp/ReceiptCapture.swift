import PhotosUI
import UIKit
import Vision
import VisionKit

/**
 * Receipts on iPhone and iPad: the document camera (it finds the receipt's edges and flattens it)
 * or a plain camera where that isn't available, the Photos picker, Vision's on-device text
 * recognition, and the share sheet for exported reports.
 */
final class ReceiptCapture: NSObject, VNDocumentCameraViewControllerDelegate, UIImagePickerControllerDelegate,
    UINavigationControllerDelegate, PHPickerViewControllerDelegate {
    /** The longest side receipt photos are kept at. */
    private static let maxSide: CGFloat = 1600
    /** Kept while a picker is open: UIKit holds its delegate weakly. */
    private static var current: ReceiptCapture?
    private var done: ((Data?, String?) -> Void)?

    static var hasCamera: Bool { UIImagePickerController.isSourceTypeAvailable(.camera) }

    static func start(camera: Bool, done: @escaping (Data?, String?) -> Void) {
        guard let top = topViewController() else { done(nil, "Can't show the camera right now."); return }
        let capture = ReceiptCapture()
        capture.done = done
        current = capture
        if camera {
            if VNDocumentCameraViewController.isSupported {
                let vc = VNDocumentCameraViewController()
                vc.delegate = capture
                top.present(vc, animated: true)
            } else if hasCamera {
                let picker = UIImagePickerController()
                picker.sourceType = .camera
                picker.delegate = capture
                top.present(picker, animated: true)
            } else {
                capture.finish(nil, "This device has no camera. Choose a photo instead.")
            }
        } else {
            var config = PHPickerConfiguration()
            config.filter = .images
            config.selectionLimit = 1
            let picker = PHPickerViewController(configuration: config)
            picker.delegate = capture
            top.present(picker, animated: true)
        }
    }

    private func finish(_ data: Data?, _ error: String?) {
        DispatchQueue.main.async {
            self.done?(data, error)
            self.done = nil
            ReceiptCapture.current = nil
        }
    }

    // MARK: Document camera

    func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFinishWith scan: VNDocumentCameraScan) {
        let image = scan.pageCount > 0 ? scan.imageOfPage(at: 0) : nil
        controller.dismiss(animated: true) { self.finish(image.flatMap(ReceiptCapture.jpeg), nil) }
    }

    func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) {
        controller.dismiss(animated: true) { self.finish(nil, nil) }
    }

    func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFailWithError error: Error) {
        controller.dismiss(animated: true) { self.finish(nil, error.localizedDescription) }
    }

    // MARK: Camera (no document camera)

    func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
        let image = info[.originalImage] as? UIImage
        picker.dismiss(animated: true) { self.finish(image.flatMap(ReceiptCapture.jpeg), nil) }
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true) { self.finish(nil, nil) }
    }

    // MARK: Photos

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard let provider = results.first?.itemProvider, provider.canLoadObject(ofClass: UIImage.self) else {
            finish(nil, results.isEmpty ? nil : "Can't read that photo.")
            return
        }
        provider.loadObject(ofClass: UIImage.self) { object, error in
            if let image = object as? UIImage {
                self.finish(ReceiptCapture.jpeg(image), nil)
            } else {
                self.finish(nil, error?.localizedDescription ?? "Can't read that photo.")
            }
        }
    }

    /** The photo upright (drawing applies its orientation), scaled down, as a JPEG. */
    static func jpeg(_ image: UIImage) -> Data? {
        let pixels = CGSize(width: image.size.width * image.scale, height: image.size.height * image.scale)
        guard pixels.width > 0, pixels.height > 0 else { return nil }
        let factor = min(1, maxSide / max(pixels.width, pixels.height))
        let target = CGSize(width: (pixels.width * factor).rounded(), height: (pixels.height * factor).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let drawn = UIGraphicsImageRenderer(size: target, format: format).image { _ in
            UIColor.white.setFill()
            UIRectFill(CGRect(origin: .zero, size: target))
            image.draw(in: CGRect(origin: .zero, size: target))
        }
        return drawn.jpegData(compressionQuality: 0.8)
    }

    // MARK: Text recognition

    /** Lines of "x, y, width, height, text" (tab-separated, fractions, y from the top), or nil. */
    static func recognizeText(_ jpeg: Data, done: @escaping (String?) -> Void) {
        guard let image = UIImage(data: jpeg)?.cgImage else { done(nil); return }
        DispatchQueue.global(qos: .userInitiated).async {
            let request = VNRecognizeTextRequest()
            request.recognitionLevel = .accurate
            request.usesLanguageCorrection = false
            do {
                try VNImageRequestHandler(cgImage: image, options: [:]).perform([request])
            } catch {
                DispatchQueue.main.async { done(nil) }
                return
            }
            let lines = (request.results ?? []).compactMap { observation -> String? in
                guard let candidate = observation.topCandidates(1).first else { return nil }
                let b = observation.boundingBox
                let text = candidate.string.replacingOccurrences(of: "\t", with: " ").replacingOccurrences(of: "\n", with: " ")
                return "\(b.origin.x)\t\(1 - b.origin.y - b.size.height)\t\(b.size.width)\t\(b.size.height)\t\(text)"
            }
            DispatchQueue.main.async { done(lines.joined(separator: "\n")) }
        }
    }

    // MARK: Sharing

    static func share(path: String, done: @escaping (Bool) -> Void) {
        guard let top = topViewController() else { done(false); return }
        let sheet = UIActivityViewController(activityItems: [URL(fileURLWithPath: path)], applicationActivities: nil)
        sheet.completionWithItemsHandler = { _, completed, _, _ in done(completed) }
        // On iPad the sheet is a popover and needs somewhere to point.
        if let popover = sheet.popoverPresentationController {
            popover.sourceView = top.view
            popover.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 0, height: 0)
            popover.permittedArrowDirections = []
        }
        top.present(sheet, animated: true)
    }

    /** The front-most screen, to show pickers over. */
    static func topViewController() -> UIViewController? {
        let windows = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap { $0.windows }
        var vc = (windows.first { $0.isKeyWindow } ?? windows.first)?.rootViewController
        while let presented = vc?.presentedViewController { vc = presented }
        return vc
    }
}
