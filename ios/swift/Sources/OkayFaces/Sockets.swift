// THE PHONE'S SOCKETS, SEALING AND SCANNING (specs/mobile.md, "Sockets, sealing, scanning"): a WebSocket each socket
// the library opens (URLSessionWebSocketTask), what comes on it told back to the library by itself
// (`okay_faces_device_event`, on its own thread); AES-256-GCM by CryptoKit, framed `iv ‖ ciphertext ‖ tag` as
// okay-serverless' Sealer frames it; a QR code read by the camera.
import Foundation
import COkayFaces
import CryptoKit
#if canImport(UIKit)
import UIKit
import AVFoundation
#endif

/// what the phone tells the library by itself: on the library's thread, as JSON
func tellLibrary(_ event: JSON) {
  let text = (try? JSONSerialization.data(withJSONObject: event)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
  ScalaThread.shared.run { text.withCString { okay_faces_device_event($0) } }
}

final class Sockets: @unchecked Sendable {
  static let shared = Sockets()
  private let lock = NSLock()
  private var open: [String: URLSessionWebSocketTask] = [:]
  private var n = 0

  /// a socket to `url`: its id; what comes on it, and its end, told to the library
  func open(_ url: String) -> JSON {
    guard let u = URL(string: url), ["ws", "wss"].contains(u.scheme ?? "") else {
      return ["error": "not a socket's address"]
    }
    let task = URLSession.shared.webSocketTask(with: u)
    lock.lock(); n += 1; let id = "s\(n)"; open[id] = task; lock.unlock()
    task.resume()
    receive(id, task)
    beat(id, task)
    return ["id": id]
  }

  private func receive(_ id: String, _ task: URLSessionWebSocketTask) {
    task.receive { [weak self] result in
      guard let self else { return }
      switch result {
      case .success(.string(let t)):
        tellLibrary(["event": "socket", "id": id, "text": t]); self.receive(id, task)
      case .success(.data(let d)):
        tellLibrary(["event": "socket", "id": id, "text": String(decoding: d, as: UTF8.self)]); self.receive(id, task)
      case .success: self.receive(id, task)
      case .failure(let e): self.ended(id, e.localizedDescription)
      }
    }
  }

  /// the line kept alive: a ping every 25 seconds (a socket idle too long is closed by the way between)
  private func beat(_ id: String, _ task: URLSessionWebSocketTask) {
    DispatchQueue.global().asyncAfter(deadline: .now() + 25) { [weak self] in
      guard let self, self.isOpen(id) else { return }
      task.sendPing { error in if let e = error { self.ended(id, e.localizedDescription) } }
      self.beat(id, task)
    }
  }

  private func isOpen(_ id: String) -> Bool { lock.lock(); defer { lock.unlock() }; return open[id] != nil }

  private func ended(_ id: String, _ why: String) {
    lock.lock(); let was = open.removeValue(forKey: id); lock.unlock()
    if was != nil { tellLibrary(["event": "closed", "id": id, "why": why]) }
  }

  func send(_ id: String, _ text: String) -> JSON {
    lock.lock(); let task = open[id]; lock.unlock()
    guard let t = task else { return ["ok": false] }
    let done = DispatchSemaphore(value: 0)
    var ok = false
    t.send(.string(text)) { error in ok = error == nil; done.signal() }
    _ = done.wait(timeout: .now() + 30)
    return ["ok": ok]
  }

  func close(_ id: String) -> JSON {
    lock.lock(); let task = open[id]; lock.unlock()
    task?.cancel(with: .normalClosure, reason: nil)
    ended(id, "closed")
    return [:]
  }
}

enum Sealing {
  static func b64(_ d: Data) -> String {
    d.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
      .replacingOccurrences(of: "=", with: "")
  }
  static func unb64(_ s: String) -> Data? {
    var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
    while t.count % 4 != 0 { t += "=" }
    return Data(base64Encoded: t)
  }
  static func seal(_ key: String, _ aad: String, _ text: String) -> JSON {
    guard let k = unb64(key), k.count == 32,
      let box = try? AES.GCM.seal(Data(text.utf8), using: SymmetricKey(data: k), nonce: AES.GCM.Nonce(),
        authenticating: Data(aad.utf8)), let all = box.combined else { return ["error": "not sealed"] }
    return ["value": b64(all)]
  }
  static func unseal(_ key: String, _ aad: String, _ text: String) -> JSON {
    guard let k = unb64(key), k.count == 32, let all = unb64(text), all.count > 28,
      let box = try? AES.GCM.SealedBox(combined: all),
      let plain = try? AES.GCM.open(box, using: SymmetricKey(data: k), authenticating: Data(aad.utf8)) else {
      return [:]
    }
    return ["value": String(decoding: plain, as: UTF8.self)]
  }
}

#if canImport(UIKit)
/// A QR CODE, read by the camera: its text; none when the person closed the reader, or there is no camera
enum Scanner {
  static func scan() -> String? {
    onMain(nil) { done in
      MainActor.assumeIsolated {
        guard let top = topmost(), AVCaptureDevice.default(for: .video) != nil else { return done(nil) }
        let reader = ReaderController(done)
        reader.modalPresentationStyle = .fullScreen
        top.present(reader, animated: true)
      }
    }
  }

  final class ReaderController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let done: (String?) -> Void
    private let session = AVCaptureSession()
    private var finished = false
    init(_ done: @escaping (String?) -> Void) { self.done = done; super.init(nibName: nil, bundle: nil) }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
      super.viewDidLoad()
      view.backgroundColor = .black
      guard let camera = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: camera),
        session.canAddInput(input) else { return finish(nil) }
      session.addInput(input)
      let output = AVCaptureMetadataOutput()
      guard session.canAddOutput(output) else { return finish(nil) }
      session.addOutput(output)
      output.setMetadataObjectsDelegate(self, queue: .main)
      output.metadataObjectTypes = [.qr]
      let preview = AVCaptureVideoPreviewLayer(session: session)
      preview.frame = view.bounds; preview.videoGravity = .resizeAspectFill
      view.layer.addSublayer(preview)
      // the system's close button: its look and its word the system's, not the face's
      let close = UIButton(type: .close)
      close.accessibilityIdentifier = "scan-cancel"
      close.addAction(UIAction { [weak self] _ in self?.finish(nil) }, for: .touchUpInside)
      close.translatesAutoresizingMaskIntoConstraints = false
      view.addSubview(close)
      NSLayoutConstraint.activate([close.centerXAnchor.constraint(equalTo: view.centerXAnchor),
        close.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -24)])
      DispatchQueue.global().async { [session] in session.startRunning() }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject],
      from connection: AVCaptureConnection) {
      if let code = objects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject }).first?.stringValue {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
        finish(code)
      }
    }

    private func finish(_ code: String?) {
      guard !finished else { return }
      finished = true
      session.stopRunning()
      dismiss(animated: true) { [done] in done(code) }
      if presentingViewController == nil { done(code) }
    }
  }
}
#endif
