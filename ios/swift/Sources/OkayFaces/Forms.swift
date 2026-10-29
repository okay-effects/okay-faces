// THE ACTIONS: a button each, or a form of the fields the library lowered — values kept by dotted name and sent as
// they are; the library reads them back along the action's schema (specs/ios.md).
import SwiftUI

struct Actions: View {
  let actions: [ActionSpec]
  let look: LookSpec
  let dialogue: Dialogue

  var body: some View {
    let buttons = actions.filter { $0.fields.isEmpty }
    let forms = actions.filter { !$0.fields.isEmpty }
    VStack(alignment: .leading, spacing: look.space * 2) {
      ForEach(forms) { a in FormView(action: a, look: look, dialogue: dialogue).id(a.name) }
      // the buttons as iOS draws its own: large, the width of the screen, in the action's tone
      VStack(spacing: look.space) {
        // one main action a screen: the form's, else the first button; the others lighter
        ForEach(Array(buttons.enumerated()), id: \.element.id) { i, a in
          Prominent(label: a.label, look: look, main: forms.isEmpty && i == 0) { dialogue.act(a.name) }
            .accessibilityIdentifier("act-\(a.name)")
        }
      }
    }
  }
}

/// a button as iOS draws a main one: large, the width there is, filled with the action's tone
struct Prominent: View {
  let label: String
  let look: LookSpec
  var main = true
  let act: () -> Void
  var body: some View {
    let r = look.rule("action")
    let tone = look.tone(r.background) ?? look.tone("accent") ?? .accentColor
    let button = Button(action: act) {
      Text(label).font(look.font(r)).frame(maxWidth: .infinity).padding(.vertical, look.space * 0.75)
    }.buttonBorderShape(.roundedRectangle(radius: 12)).controlSize(.large).tint(tone)
    if main {
      button.buttonStyle(.borderedProminent).foregroundStyle(look.tone(r.color) ?? .white)
    } else {
      button.buttonStyle(.bordered).foregroundStyle(tone)
    }
  }
}

/// one action's form: its fields, those held back under the look's "Options", the button
struct FormView: View {
  let action: ActionSpec
  let look: LookSpec
  let dialogue: Dialogue
  @State private var values: [String: [String]] = [:]
  @State private var counts: [String: Int] = [:]

  var body: some View {
    VStack(alignment: .leading, spacing: look.space * 1.5) {
      Text(action.label.uppercased()).font(look.small).foregroundStyle(look.tone("muted") ?? .secondary)
      if let d = action.doc { Text(d).dressed(look, "doc") }
      ForEach(action.fields.filter { !$0.more }) { f in control(f, prefix: nil) }
      let held = action.fields.filter { $0.more }
      if !held.isEmpty {
        DisclosureGroup(look.word("options", "Options")) {
          ForEach(held) { f in control(f, prefix: nil) }
        }.dressed(look, "more")
      }
      Prominent(label: action.label, look: look) { dialogue.act(action.name, values) }
        .accessibilityIdentifier("act-\(action.name)").padding(.top, look.space / 2)
    }
    .card(look)
    .onAppear { defaults(action.fields) }
  }

  private func defaults(_ fs: [Field]) {
    for f in fs {
      if let d = f.defaultValue, values[f.name] == nil { values[f.name] = [d] }
      if f.kind == "choice", f.required, values[f.name] == nil, let o = f.options.first { values[f.name] = [o] }
      if f.kind == "oneof", values[f.name + ".type"] == nil, let c = f.cases.first {
        values[f.name + ".type"] = [c.name]
      }
      if f.kind == "date", f.required, values[f.name] == nil { values[f.name] = [Self.day(Date())] }
      defaults(f.fields)
      f.cases.forEach { defaults($0.fields) }
    }
  }

  private func text(_ name: String) -> Binding<String> {
    Binding(get: { values[name]?.first ?? "" }, set: { values[name] = $0.isEmpty ? nil : [$0] })
  }

  /// a day as the library reads one: `yyyy-mm-dd`
  static let days: DateFormatter = {
    let f = DateFormatter(); f.dateFormat = "yyyy-MM-dd"; f.locale = Locale(identifier: "en_US_POSIX"); return f
  }()
  static func day(_ d: Date) -> String { days.string(from: d) }

  /// a field's name inside an item of a list: `#` its index
  private func named(_ f: Field, _ prefix: (String, Int)?) -> String {
    guard let (list, i) = prefix else { return f.name }
    return f.name.replacingOccurrences(of: "\(list).#", with: "\(list).\(i)")
  }

  private func control(_ f: Field, prefix: (String, Int)?) -> AnyView {
    let name = named(f, prefix)
    return AnyView(VStack(alignment: .leading, spacing: 2) {
      switch f.kind {
      case "bool":
        Toggle(f.label, isOn: Binding(get: { values[name]?.first == "true" },
          set: { values[name] = $0 ? ["true"] : nil }))
      case "group":
        Text(f.label).dressed(look, "label")
        ForEach(f.fields) { g in control(g, prefix: prefix) }
      case "oneof":
        Text(f.label).dressed(look, "label")
        Picker(f.label, selection: Binding(get: { values[name + ".type"]?.first ?? "" },
          set: { values[name + ".type"] = [$0] })) {
          ForEach(f.cases, id: \.name) { Text($0.name).tag($0.name) }
        }.pickerStyle(.segmented)
        if let c = f.cases.first(where: { $0.name == values[name + ".type"]?.first }) {
          ForEach(c.fields) { g in control(g, prefix: prefix) }
        }
      case "many":
        Text(f.label).dressed(look, "label")
        let n = counts[name] ?? max(f.min, 1)
        ForEach(0..<n, id: \.self) { i in
          VStack(alignment: .leading) {
            ForEach(f.item) { g in control(g, prefix: (name, i)) }
          }.padding(.leading, look.space)
        }
        HStack {
          if f.max.map({ n < $0 }) ?? true {
            Button(look.word("add", "Add")) { counts[name] = n + 1 }
          }
          if n > f.min && n > 0 {
            Button(look.word("remove", "Remove")) {
              counts[name] = n - 1
              values = values.filter { !$0.key.hasPrefix("\(name).\(n - 1).") }
            }
          }
        }.foregroundStyle(look.tone("accent") ?? .accentColor)
      default:
        Text(f.label).font(look.small).foregroundStyle(look.tone("muted") ?? .secondary)
        leaf(f, name)
      }
      if let d = f.doc {
        Text(d).font(.system(size: look.size * 0.75)).foregroundStyle(look.tone("muted") ?? .secondary)
      }
    })
  }

  @ViewBuilder
  private func leaf(_ f: Field, _ name: String) -> some View {
    switch f.kind {
    case "long", "lines":
      TextEditor(text: text(name)).frame(minHeight: 80).scrollContentBackground(.hidden)
        .accessibilityIdentifier("field-\(name)").filled(look)
    case "secret": SecureField(f.label, text: text(name)).filled(look)
    case "integer": TextField(f.label, text: text(name)).keyboardType(.numberPad).filled(look)
    case "number": TextField(f.label, text: text(name)).keyboardType(.decimalPad).filled(look)
    case "choice":
      Picker(f.label, selection: Binding(get: { values[name]?.first ?? "" },
        set: { values[name] = $0.isEmpty ? nil : [$0] })) {
        if !f.required { Text(look.word("optional", "—")).tag("") }
        ForEach(f.options, id: \.self) { Text($0).tag($0) }
      }.pickerStyle(.menu)
    case "date":
      if f.required || values[name] != nil {
        HStack {
          DatePicker(f.label, selection: Binding(
            get: { values[name]?.first.flatMap(Self.parse) ?? Date() },
            set: { values[name] = [Self.day($0)] }), displayedComponents: .date).labelsHidden()
          if !f.required {
            Button(look.word("remove", "Remove")) { values[name] = nil }
              .foregroundStyle(look.tone("accent") ?? .accentColor)
          }
        }
      } else {
        Button(look.word("add", "Add")) { values[name] = [Self.day(Date())] }
          .foregroundStyle(look.tone("accent") ?? .accentColor).accessibilityIdentifier("add-\(name)")
      }
    default:
      TextField(f.label, text: text(name)).accessibilityIdentifier("field-\(name)").filled(look)
    }
  }

  static func parse(_ s: String) -> Date? { days.date(from: s) }
}
