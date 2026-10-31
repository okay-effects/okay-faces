// THE PHONE'S OTHER POWERS, as `Device` asks them (specs/mobile.md): a photo taken or picked, where the phone is,
// sharing, the person checked. Each called on the library's thread and waiting there; what it shows is put on the
// main thread, over the app's topmost screen.
import Foundation
import SwiftUI
import UIKit
import PhotosUI
import CoreLocation
import LocalAuthentication

/// the screen on top, to present over
@MainActor func topmost() -> UIViewController? {
  let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
  var top = scene?.windows.first(where: { $0.isKeyWindow })?.rootViewController
  while let next = top?.presentedViewController { top = next }
  return top
}

/// on the main thread, and waited for here: what `work` hands to `done`
func onMain<T>(_ fallback: T, _ work: @escaping (@escaping (T) -> Void) -> Void) -> T {
  let finished = DispatchSemaphore(value: 0)
  var result = fallback
  DispatchQueue.main.async { work { result = $0; finished.signal() } }
  finished.wait()
  return result
}

/// A PHOTO: from the camera when there is one and it is asked, else picked; a JPEG at most `size` across, as data:
enum Photos {
  static func take(_ from: String, _ size: Int) -> String? {
    let image: UIImage? = onMain(nil) { done in
      MainActor.assumeIsolated {
        guard let top = topmost() else { return done(nil) }
        if from == "camera" && UIImagePickerController.isSourceTypeAvailable(.camera) {
          let picker = UIImagePickerController()
          picker.sourceType = .camera
          let d = CameraDelegate(done); picker.delegate = d; objc_setAssociatedObject(picker, &key, d, .OBJC_ASSOCIATION_RETAIN)
          top.present(picker, animated: true)
        } else {
          var config = PHPickerConfiguration(); config.filter = .images; config.selectionLimit = 1
          let picker = PHPickerViewController(configuration: config)
          let d = PickerDelegate(done); picker.delegate = d; objc_setAssociatedObject(picker, &key, d, .OBJC_ASSOCIATION_RETAIN)
          top.present(picker, animated: true)
        }
      }
    }
    guard let i = image else { return nil }
    let k = min(1, CGFloat(size) / max(i.size.width, i.size.height))
    let target = CGSize(width: i.size.width * k, height: i.size.height * k)
    let scaled = UIGraphicsImageRenderer(size: target).image { _ in i.draw(in: CGRect(origin: .zero, size: target)) }
    return scaled.jpegData(compressionQuality: 0.8).map { "data:image/jpeg;base64," + $0.base64EncodedString() }
  }
  nonisolated(unsafe) static var key = 0

  final class PickerDelegate: NSObject, PHPickerViewControllerDelegate {
    let done: (UIImage?) -> Void
    init(_ done: @escaping (UIImage?) -> Void) { self.done = done }
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
      picker.dismiss(animated: true)
      guard let p = results.first?.itemProvider, p.canLoadObject(ofClass: UIImage.self) else { return done(nil) }
      p.loadObject(ofClass: UIImage.self) { image, _ in self.done(image as? UIImage) }
    }
  }
  final class CameraDelegate: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    let done: (UIImage?) -> Void
    init(_ done: @escaping (UIImage?) -> Void) { self.done = done }
    func imagePickerController(_ picker: UIImagePickerController,
      didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
      picker.dismiss(animated: true); done(info[.originalImage] as? UIImage)
    }
    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { picker.dismiss(animated: true); done(nil) }
  }
}

/// WHERE THE PHONE IS: asked once for permission, then one fix
final class Where: NSObject, CLLocationManagerDelegate {
  private let manager = CLLocationManager()
  private var done: ((CLLocation?) -> Void)?

  static func now() -> JSON {
    let place: CLLocation? = onMain(nil) { done in
      let w = Where(); w.done = { done($0); _ = w }
      w.manager.delegate = w
      w.manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
      w.start()
    }
    guard let p = place else { return [:] }
    return ["latitude": p.coordinate.latitude, "longitude": p.coordinate.longitude,
            "accuracy": p.horizontalAccuracy]
  }
  private func start() {
    switch manager.authorizationStatus {
    case .notDetermined: manager.requestWhenInUseAuthorization()
    case .authorizedWhenInUse, .authorizedAlways: manager.requestLocation()
    default: finish(nil)
    }
  }
  func locationManagerDidChangeAuthorization(_ m: CLLocationManager) {
    if m.authorizationStatus != .notDetermined { start() }
  }
  func locationManager(_ m: CLLocationManager, didUpdateLocations ls: [CLLocation]) { finish(ls.last) }
  func locationManager(_ m: CLLocationManager, didFailWithError e: Error) { finish(nil) }
  private func finish(_ p: CLLocation?) { done?(p); done = nil }
}

/// SHARING: the phone's own sheet, its text and link; whether it went
enum Sharing {
  static func share(_ text: String, _ url: String?) -> Bool {
    onMain(false) { done in
      MainActor.assumeIsolated {
        guard let top = topmost() else { return done(false) }
        var items: [Any] = [text]
        if let u = url.flatMap(URL.init(string:)) { items.append(u) }
        let sheet = UIActivityViewController(activityItems: items, applicationActivities: nil)
        sheet.completionWithItemsHandler = { _, completed, _, _ in done(completed) }
        top.present(sheet, animated: true)
      }
    }
  }
}

/// THE PERSON THEMSELVES: Face ID, Touch ID, or the passcode
enum Person {
  static func verify(_ reason: String) -> Bool {
    let context = LAContext()
    var error: NSError?
    guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else { return false }
    let done = DispatchSemaphore(value: 0)
    var ok = false
    context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { success, _ in ok = success; done.signal() }
    done.wait()
    return ok
  }
}
