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
  var hexes: [String: String] = [:]
  var size = 16.0, space = 8.0
  var serif = false, mono = false
  var rules: [String: Rule] = [:]
  var words: [String: String] = [:]

  init() {}
  /// the look the application gives, its palette at night when `dark` and it has one; its sizes times `scale`
  init(_ o: JSON, dark: Bool = false, scale: Double = 1) {
    let theme = obj(o["theme"])
    let night = obj(obj(o["dark"])["palette"])
    for (tone, hex) in (dark && !night.isEmpty ? night : obj(theme["palette"])) {
      if let h = hex as? String { palette[tone] = Color(hex: h); hexes[tone] = h }
    }
    size = (num(theme["size"]) ?? 16) * scale; space = num(theme["space"]) ?? 8
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
  /// a hex colour's channels, 0 to 1
  static func rgb(_ hex: String) -> (Double, Double, Double) {
    let v = UInt64(hex.hasPrefix("#") ? String(hex.dropFirst()) : hex, radix: 16) ?? 0
    return (Double((v >> 16) & 0xff) / 255, Double((v >> 8) & 0xff) / 255, Double(v & 0xff) / 255)
  }
  init(hex: String) {
    let h = hex.hasPrefix("#") ? String(hex.dropFirst()) : hex
    let v = UInt64(h, radix: 16) ?? 0
    self.init(red: Double((v >> 16) & 0xff) / 255, green: Double((v >> 8) & 0xff) / 255,
      blue: Double(v & 0xff) / 255)
  }
}

/// what a part takes from the part around it, as the web's text does: its colour, weight, typeface, and its size as a
/// multiple — a cell marked 1.6 makes the mark inside it 1.6
struct Inherited { var color: String?; var bold = false; var mono = false; var size = 1.0; var centered = false }
private struct InheritedKey: EnvironmentKey { static let defaultValue = Inherited() }
extension EnvironmentValues {
  var okayInherited: Inherited {
    get { self[InheritedKey.self] }
    set { self[InheritedKey.self] = newValue }
  }
}

/// A PART DRESSED BY ITS ROLE'S RULE: its font, its tones, its padding, its border
struct Dressed: ViewModifier {
  let look: LookSpec
  let roles: [String]
  @Environment(\.okayInherited) private var around

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
    let inner = Inherited(color: r.color ?? around.color, bold: r.bold || around.bold, mono: r.mono || around.mono,
      size: r.size * around.size, centered: r.centered || around.centered)
    var font = r; font.bold = inner.bold; font.mono = inner.mono; font.size = inner.size
    return content
      .environment(\.okayInherited, inner)
      .font(look.font(font))
      .foregroundStyle(look.tone(inner.color) ?? look.tone("fg") ?? .primary)
      .multilineTextAlignment(r.centered ? .center : .leading)
      // a centred part fills what it is given, its content in the middle: a cell of a board
      .frame(maxWidth: r.centered ? .infinity : nil, maxHeight: r.centered && r.border != nil ? .infinity : nil,
        alignment: .center)
      .padding(pad)
      .background(look.tone(r.background) ?? .clear, in: RoundedRectangle(cornerRadius: pad > 0 ? 6 : 0))
      .overlay {
        if let b = look.tone(r.border) { RoundedRectangle(cornerRadius: 6).stroke(b, lineWidth: 1) }
      }
  }
}

extension View {
  func dressed(_ look: LookSpec, _ roles: String...) -> some View { modifier(Dressed(look: look, roles: roles)) }

  /// A CARD, as iOS groups what belongs together: the look's ground, rounded, on the page's surface
  func card(_ look: LookSpec) -> some View {
    padding(look.space * 2)
      .frame(maxWidth: .infinity, alignment: .leading)
      .background(look.tone("bg") ?? Color(.systemBackground),
        in: RoundedRectangle(cornerRadius: 16, style: .continuous))
  }

  /// a field's ground: filled with the surface tone, rounded, as iOS draws its inputs
  func filled(_ look: LookSpec) -> some View {
    padding(.horizontal, look.space * 1.5).padding(.vertical, look.space * 1.25)
      .background(look.tone("surface") ?? Color(.secondarySystemBackground),
        in: RoundedRectangle(cornerRadius: 10, style: .continuous))
  }
}

extension LookSpec {
  /// the page's tone: a shade off the cards', as iOS's grouped screens are
  var page: Color {
    guard let bg = hexes["bg"].map(Color.rgb), let fg = hexes["fg"].map(Color.rgb) else {
      return Color(.systemGroupedBackground)
    }
    let k = 0.05
    return Color(red: bg.0 + (fg.0 - bg.0) * k, green: bg.1 + (fg.1 - bg.1) * k, blue: bg.2 + (fg.2 - bg.2) * k)
  }
  /// a small label's font: a field's name, a form's title
  var small: Font { .system(size: size * 0.8, weight: .semibold) }
}
