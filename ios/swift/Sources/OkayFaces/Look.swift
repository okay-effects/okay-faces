// HOW IT LOOKS, as the application says (okay-faces' `Look`): tones, a rule per role, the face's words. The face
// interprets them; it has no colour, size or word of its own.
import SwiftUI

struct Rule {
  var color: String?, background: String?, border: String?
  var bold = false, mono = false, centered = false
  var size = 1.0
  var pad = 0, gap = 0
  var row = false

  init() {}
  init(_ o: JSON) {
    color = str(o["color"]); background = str(o["background"]); border = str(o["border"])
    bold = bool(o["bold"]); mono = bool(o["mono"]); centered = bool(o["centered"])
    size = num(o["size"]) ?? 1; pad = Int(num(o["pad"]) ?? 0); gap = Int(num(o["gap"]) ?? 0)
    row = str(o["flow"]) == "row"
  }
}

struct LookSpec {
  var palette: [String: Color] = [:]
  var size = 16.0, space = 8.0
  var serif = false, mono = false
  var rules: [String: Rule] = [:]
  var words: [String: String] = [:]

  init() {}
  init(_ o: JSON) {
    let theme = obj(o["theme"])
    for (tone, hex) in obj(theme["palette"]) { if let h = hex as? String { palette[tone] = Color(hex: h) } }
    size = num(theme["size"]) ?? 16; space = num(theme["space"]) ?? 8
    serif = str(theme["font"]) == "serif"; mono = str(theme["font"]) == "mono"
    for (role, r) in obj(o["rules"]) { rules[role] = Rule(obj(r)) }
    for (k, w) in obj(o["words"]) { if let s = w as? String { words[k] = s } }
  }

  func rule(_ role: String) -> Rule { rules[role] ?? Rule() }
  func tone(_ t: String?) -> Color? { t.flatMap { palette[$0] } }
  func word(_ k: String, _ otherwise: String) -> String { words[k] ?? otherwise }
  func spacing(_ role: String) -> CGFloat { CGFloat(rule(role).gap) * space }
  /// a mark's colour in a picture: its background, else its colour, else the accent
  func markColor(_ mark: String) -> Color {
    let r = rule("mark-\(mark)")
    return tone(r.background) ?? tone(r.color) ?? tone("accent") ?? .accentColor
  }
  func font(_ r: Rule) -> Font {
    let design: Font.Design = r.mono || mono ? .monospaced : serif ? .serif : .default
    return .system(size: size * r.size, weight: r.bold ? .bold : .regular, design: design)
  }
}

extension Color {
  init(hex: String) {
    let h = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
    let v = UInt64(h, radix: 16) ?? 0
    self.init(red: Double((v >> 16) & 0xff) / 255, green: Double((v >> 8) & 0xff) / 255,
      blue: Double(v & 0xff) / 255)
  }
}

/// A PART DRESSED BY ITS ROLE'S RULE: its font, its tones, its padding, its border
struct Dressed: ViewModifier {
  let look: LookSpec
  let roles: [String]

  func body(content: Content) -> some View {
    // general to specific: a level-1 heading is a heading; the specific one's word wins where it says something
    let r = roles.map { look.rule($0) }.reduce(Rule()) { acc, x in
      var m = acc
      if x.color != nil { m.color = x.color }; if x.background != nil { m.background = x.background }
      if x.border != nil { m.border = x.border }
      m.bold = m.bold || x.bold; m.mono = m.mono || x.mono; m.centered = m.centered || x.centered
      if x.size != 1 { m.size = x.size }; if x.pad != 0 { m.pad = x.pad }; if x.gap != 0 { m.gap = x.gap }
      m.row = m.row || x.row
      return m
    }
    let pad = CGFloat(r.pad) * look.space
    return content
      .font(look.font(r))
      .foregroundStyle(look.tone(r.color) ?? look.tone("fg") ?? .primary)
      .multilineTextAlignment(r.centered ? .center : .leading)
      .padding(pad)
      .background(look.tone(r.background) ?? .clear, in: RoundedRectangle(cornerRadius: pad > 0 ? 6 : 0))
      .overlay {
        if let b = look.tone(r.border) { RoundedRectangle(cornerRadius: 6).stroke(b, lineWidth: 1) }
      }
  }
}

extension View {
  func dressed(_ look: LookSpec, _ roles: String...) -> some View { modifier(Dressed(look: look, roles: roles)) }
}
