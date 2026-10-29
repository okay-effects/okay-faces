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
    VStack(alignment: .leading, spacing: look.spacing("actions")) {
      ForEach(forms) { a in FormView(action: a, look: look, dialogue: dialogue).id(a.name) }
      if !buttons.isEmpty {
        Flow(row: look.rule("actions").row, spacing: look.spacing("actions")) {
          ForEach(buttons) { a in
            Button { dialogue.act(a.name) } label: { Text(a.label).dressed(look, "action") }.buttonStyle(.plain)
          }
        }
      }
    }.dressed(look, "actions")
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
    VStack(alignment: .leading, spacing: look.spacing("form")) {
      if let d = action.doc { Text(d).dressed(look, "doc") }
      ForEach(action.fields.filter { !$0.more }) { f in control(f, prefix: nil) }
      let held = action.fields.filter { $0.more }
      if !held.isEmpty {
        DisclosureGroup(look.word("options", "Options")) {
          ForEach(held) { f in control(f, prefix: nil) }
        }.dressed(look, "more")
      }
      Button { dialogue.act(action.name, values) } label: { Text(action.label).dressed(look, "action") }
        .buttonStyle(.plain)
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .dressed(look, "form")
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
        }
      default:
        Text(f.label + (f.required ? "" : " ")).dressed(look, "label")
        leaf(f, name)
      }
      if let d = f.doc { Text(d).dressed(look, "doc") }
    }.dressed(look, "field"))
  }

  @ViewBuilder
  private func leaf(_ f: Field, _ name: String) -> some View {
    switch f.kind {
    case "long", "lines":
      TextEditor(text: text(name)).frame(minHeight: 80)
        .overlay(RoundedRectangle(cornerRadius: 6).stroke(look.tone("border") ?? .gray))
    case "secret": SecureField(f.label, text: text(name)).textFieldStyle(.roundedBorder)
    case "integer": TextField(f.label, text: text(name)).keyboardType(.numberPad).textFieldStyle(.roundedBorder)
    case "number": TextField(f.label, text: text(name)).keyboardType(.decimalPad).textFieldStyle(.roundedBorder)
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
          if !f.required { Button(look.word("remove", "Remove")) { values[name] = nil } }
        }
      } else {
        Button(look.word("add", "Add")) { values[name] = [Self.day(Date())] }
      }
    default: TextField(f.label, text: text(name)).textFieldStyle(.roundedBorder)
    }
  }

  static func parse(_ s: String) -> Date? { days.date(from: s) }
}
