// THE PHONE, AS THE PROGRAM ASKS IT (specs/mobile.md, "The device"): each operation of the library's `Device` effect
// answered here by the system — URLSession, UserDefaults, the keychain, UserNotifications, the opening of a link.
// Called on the library's own thread (`ScalaThread`), never the main one: an operation may wait for the network.
import Foundation
import COkayFaces
import Security
import UserNotifications
#if canImport(UIKit)
import UIKit
#endif

enum DeviceBridge {
  /// the function the library calls: JSON in, JSON out, made by `strdup` for the library to free
  static let function: @convention(c) (UnsafePointer<CChar>?) -> UnsafeMutablePointer<CChar>? = { request in
    let text = request.map { String(cString: $0) } ?? "{}"
    let data = Data(text.utf8)
    let op = (try? JSONSerialization.jsonObject(with: data)) as? JSON ?? [:]
    let answer = DeviceBridge.answer(op)
    let out = (try? JSONSerialization.data(withJSONObject: answer)).map { String(decoding: $0, as: UTF8.self) } ?? "{}"
    return strdup(out)
  }

  static func answer(_ op: JSON) -> JSON {
    switch str(op["op"]) ?? "" {
    case "fetch": return fetch(op)
    case "load": return value(UserDefaults.standard.string(forKey: key(op)))
    case "save":
      if let v = str(op["value"]) { UserDefaults.standard.set(v, forKey: key(op)) }
      else { UserDefaults.standard.removeObject(forKey: key(op)) }
      return [:]
    case "secret": return value(Keychain.get(str(op["key"]) ?? ""))
    case "hide": Keychain.put(str(op["key"]) ?? "", str(op["value"])); return [:]
    case "now": return ["value": Int64(Date().timeIntervalSince1970 * 1000)]
    case "notify":
      return ["ok": Notifications.post(str(op["title"]) ?? "", str(op["body"]) ?? "", num(op["after"]) ?? 0)]
    case "open": return ["ok": open(str(op["url"]) ?? "")]
    case let other: return ["error": "no operation \(other)"]
    }
  }

  private static func key(_ op: JSON) -> String { "okay-faces." + (str(op["key"]) ?? "") }
  private static func value(_ v: String?) -> JSON { v.map { ["value": $0] } ?? [:] }

  /// the network, waited for: a reply, or status 0 and the reason
  private static func fetch(_ op: JSON) -> JSON {
    guard let url = URL(string: str(op["url"]) ?? "") else { return ["status": 0, "error": "not an address"] }
    var request = URLRequest(url: url, timeoutInterval: 30)
    request.httpMethod = str(op["method"]) ?? "GET"
    for (k, v) in obj(op["headers"]) { request.setValue(v as? String, forHTTPHeaderField: k) }
    if let b = str(op["body"]), !b.isEmpty { request.httpBody = Data(b.utf8) }
    let done = DispatchSemaphore(value: 0)
    var answer: JSON = ["status": 0, "error": "no answer"]
    URLSession.shared.dataTask(with: request) { data, response, error in
      if let e = error {
        answer = ["status": 0, "error": e.localizedDescription]
      } else if let r = response as? HTTPURLResponse {
        var headers: [String: String] = [:]
        for (k, v) in r.allHeaderFields { headers["\(k)"] = "\(v)" }
        answer = ["status": r.statusCode, "headers": headers,
          "body": data.map { String(decoding: $0, as: UTF8.self) } ?? ""]
      }
      done.signal()
    }.resume()
    done.wait()
    return answer
  }

  private static func open(_ s: String) -> Bool {
    #if canImport(UIKit)
    guard let url = URL(string: s) else { return false }
    DispatchQueue.main.async { UIApplication.shared.open(url) }
    return true
    #else
    return false
    #endif
  }
}

/// SECRETS, in the keychain: generic passwords of the service `okay-faces`, by key
enum Keychain {
  private static func query(_ key: String) -> [String: Any] {
    [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "okay-faces",
     kSecAttrAccount as String: key]
  }
  static func get(_ key: String) -> String? {
    var q = query(key)
    q[kSecReturnData as String] = true
    q[kSecMatchLimit as String] = kSecMatchLimitOne
    var out: AnyObject?
    guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
    return String(decoding: d, as: UTF8.self)
  }
  static func put(_ key: String, _ value: String?) {
    SecItemDelete(query(key) as CFDictionary)
    guard let v = value else { return }
    var q = query(key)
    q[kSecValueData as String] = Data(v.utf8)
    q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
    SecItemAdd(q as CFDictionary, nil)
  }
}

/// NOTIFICATIONS: asked for once, shown even while the app is open
final class Notifications: NSObject, UNUserNotificationCenterDelegate {
  private static let shared = Notifications()
  static func post(_ title: String, _ body: String, _ after: Double) -> Bool {
    let center = UNUserNotificationCenter.current()
    center.delegate = shared
    let asked = DispatchSemaphore(value: 0)
    var allowed = false
    center.requestAuthorization(options: [.alert, .sound]) { ok, _ in allowed = ok; asked.signal() }
    asked.wait()
    guard allowed else { return false }
    let content = UNMutableNotificationContent()
    content.title = title; content.body = body; content.sound = .default
    let trigger = after > 0 ? UNTimeIntervalNotificationTrigger(timeInterval: after, repeats: false) : nil
    center.add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: trigger))
    return true
  }
  func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
    withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void) {
    done([.banner, .sound])
  }
}

/// THE LIBRARY'S THREAD: Scala Native is started and called on this one thread alone, so its collector sees one
/// stack; a block given is run there in turn, and the caller is not kept waiting
final class ScalaThread: Thread {
  static let shared: ScalaThread = { let t = ScalaThread(); t.name = "okay-faces"; t.start(); return t }()
  private let lock = NSCondition()
  private var blocks: [() -> Void] = []

  func run(_ block: @escaping () -> Void) {
    lock.lock(); blocks.append(block); lock.signal(); lock.unlock()
  }
  override func main() {
    _ = ScalaNativeInit()
    okay_faces_on_device(DeviceBridge.function)
    while true {
      lock.lock()
      while blocks.isEmpty { lock.wait() }
      let next = blocks.removeFirst()
      lock.unlock()
      next()
    }
  }
}
