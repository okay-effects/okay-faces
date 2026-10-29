// What crosses from the application's library: a screen, its views, its actions' fields, the look (specs/ios.md).
// Read from JSON as it comes; nothing here knows an application.
import Foundation

typealias JSON = [String: Any]

func str(_ j: Any?) -> String? { j as? String }
func num(_ j: Any?) -> Double? { (j as? NSNumber)?.doubleValue }
func bool(_ j: Any?) -> Bool { (j as? NSNumber)?.boolValue ?? false }
func list(_ j: Any?) -> [Any] { j as? [Any] ?? [] }
func obj(_ j: Any?) -> JSON { j as? JSON ?? [:] }

/// A VIEW, as `ViewJson` writes it
indirect enum ViewNode {
  case empty
  case text(String)
  case heading(Int, String)
  case notice(String, String)
  case fields([(String, ViewNode)])
  case table([String], [[ViewNode]])
  case items([ViewNode])
  case group(String?, [ViewNode])
  case ref(String, String)
  case link(String, String)
  case datum(String)
  case masked(String)
  case marked(String, ViewNode)
  case picture(Picture)

  init(_ j: Any?) {
    let o = obj(j)
    switch str(o["type"]) ?? "" {
    case "text": self = .text(str(o["text"]) ?? "")
    case "heading": self = .heading(Int(num(o["level"]) ?? 1), str(o["text"]) ?? "")
    case "notice": self = .notice(str(o["level"]) ?? "info", str(o["text"]) ?? "")
    case "fields": self = .fields(list(o["rows"]).map { r in (str(obj(r)["label"]) ?? "", ViewNode(obj(r)["value"])) })
    case "table":
      self = .table(list(o["columns"]).compactMap(str), list(o["rows"]).map { list($0).map { ViewNode($0) } })
    case "items": self = .items(list(o["items"]).map { ViewNode($0) })
    case "group": self = .group(str(o["title"]), list(o["children"]).map { ViewNode($0) })
    case "ref": self = .ref(str(o["key"]) ?? "", str(o["label"]) ?? "")
    case "link": self = .link(str(o["label"]) ?? "", str(o["url"]) ?? "")
    case "datum":
      let data = (try? JSONSerialization.data(withJSONObject: o["value"] ?? NSNull(),
        options: [.fragmentsAllowed, .prettyPrinted, .sortedKeys])) ?? Data()
      self = .datum(String(decoding: data, as: UTF8.self))
    case "masked": self = .masked(str(o["hint"]) ?? "")
    case "marked": self = .marked(str(o["mark"]) ?? "", ViewNode(o["view"]))
    case "picture": self = .picture(Picture(o))
    default: self = .empty
    }
  }
}

/// A PICTURE THE PROGRAM DESCRIBES: shapes on a canvas, each marked with the application's word for its look
struct Picture {
  enum Shape {
    case box(x: Double, y: Double, w: Double, h: Double, mark: String, title: String)
    case band(x0: Double, y0: Double, x1: Double, y1: Double, thickness: Double, mark: String, title: String)
    case label(x: Double, y: Double, text: String, anchor: String)
  }
  let width: Double, height: Double, text: String
  let shapes: [Shape]

  init(_ o: JSON) {
    width = num(o["width"]) ?? 1; height = num(o["height"]) ?? 1; text = str(o["text"]) ?? ""
    shapes = list(o["shapes"]).compactMap { s in
      let s = obj(s)
      func n(_ k: String) -> Double { num(s[k]) ?? 0 }
      switch str(s["shape"]) {
      case "box": return .box(x: n("x"), y: n("y"), w: n("w"), h: n("h"), mark: str(s["mark"]) ?? "",
        title: str(s["title"]) ?? "")
      case "band": return .band(x0: n("x0"), y0: n("y0"), x1: n("x1"), y1: n("y1"), thickness: n("thickness"),
        mark: str(s["mark"]) ?? "", title: str(s["title"]) ?? "")
      case "label": return .label(x: n("x"), y: n("y"), text: str(s["text"]) ?? "", anchor: str(s["anchor"]) ?? "start")
      default: return nil
      }
    }
  }
}

/// ONE FIELD OF A FORM, lowered by the library: its dotted name, a kind a control is chosen by
struct Field: Identifiable {
  struct Case { let name: String; let fields: [Field] }
  let name: String, kind: String, label: String
  let doc: String?, defaultValue: String?
  let required: Bool, more: Bool
  let options: [String]
  let fields: [Field]
  let cases: [Case]
  let item: [Field]
  let min: Int, max: Int?
  var id: String { name }

  init(_ j: Any?) {
    let o = obj(j)
    name = str(o["name"]) ?? ""; kind = str(o["kind"]) ?? "text"; label = str(o["label"]) ?? ""
    doc = str(o["doc"]); defaultValue = str(o["default"])
    required = bool(o["required"]); more = bool(o["more"])
    options = list(o["options"]).compactMap(str)
    fields = list(o["fields"]).map { Field($0) }
    cases = list(o["cases"]).map { c in
      Case(name: str(obj(c)["name"]) ?? "", fields: list(obj(c)["fields"]).map { Field($0) })
    }
    item = list(o["item"]).map { Field($0) }
    min = Int(num(o["min"]) ?? 0); max = num(o["max"]).map { Int($0) }
  }
}

/// ONE ACTION: a button, or a form of fields
struct ActionSpec: Identifiable {
  let name: String, label: String, doc: String?
  let fields: [Field]
  var id: String { name }
  init(_ j: Any?) {
    let o = obj(j)
    name = str(o["name"]) ?? ""; label = str(o["label"]) ?? ""; doc = str(o["doc"])
    fields = list(o["fields"]).map { Field($0) }
  }
}

/// THE SCREEN: what was told, the step's view, its actions, the last choice's problems
struct Screen {
  let title: String?
  let told: [ViewNode]
  let view: ViewNode
  let actions: [ActionSpec]
  let problems: [String]
  let back: Bool, done: Bool
  let error: String?

  init(_ o: JSON) {
    title = str(o["title"]); error = str(o["error"])
    told = list(o["told"]).map { ViewNode($0) }
    view = ViewNode(o["view"])
    actions = list(o["actions"]).map { ActionSpec($0) }
    problems = list(o["problems"]).map { p in
      let path = list(obj(p)["path"]).compactMap(str).joined(separator: ".")
      let message = str(obj(p)["message"]) ?? ""
      return path.isEmpty ? message : "\(path): \(message)"
    }
    back = bool(o["back"]); done = bool(o["done"])
  }
  static let empty = Screen([:])

  /// whether a `Ref` is live: the action `ref` is offered
  var refs: Bool { actions.contains { $0.name == "ref" } }
}
