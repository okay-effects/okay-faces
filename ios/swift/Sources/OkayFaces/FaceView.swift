// THE FACE: the screen drawn — what was told, the step's view, the problems, the actions — in the application's look.
// `OkayFacesView(app: "my.App")` is the whole of an app's SwiftUI.
import SwiftUI

public struct OkayFacesView: View {
  @StateObject private var dialogue: Dialogue

  public init(app: String) { _dialogue = StateObject(wrappedValue: Dialogue(app: app)) }

  public var body: some View {
    let look = dialogue.look
    let screen = dialogue.screen
    NavigationStack {
      ScrollViewReader { scroll in
        ScrollView {
          VStack(alignment: .leading, spacing: look.spacing("face")) {
            if let e = screen.error { Text(e).dressed(look, "notice", "notice-problem") }
            ForEach(Array(screen.told.enumerated()), id: \.offset) { _, v in
              Node(view: v, look: look, dialogue: dialogue, refs: false).frame(maxWidth: .infinity, alignment: .leading)
                .dressed(look, "told")
            }
            Node(view: screen.view, look: look, dialogue: dialogue, refs: screen.refs)
              .frame(maxWidth: .infinity, alignment: .leading).dressed(look, "view")
            if !screen.problems.isEmpty {
              VStack(alignment: .leading) { ForEach(screen.problems, id: \.self) { Text($0) } }
                .dressed(look, "problems")
            }
            Actions(actions: screen.actions.filter { $0.name != "ref" }, look: look, dialogue: dialogue)
            Color.clear.frame(height: 1).id("end")
          }
          .padding(CGFloat(look.rule("face").pad) * look.space)
        }
        .onChange(of: screen.told.count + screen.actions.count) { scroll.scrollTo("end") }
      }
      .background(look.tone("bg") ?? Color(.systemBackground))
      .navigationTitle(screen.title ?? "")
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        if screen.back {
          ToolbarItem(placement: .topBarLeading) {
            Button(look.word("back", "Back")) { dialogue.back() }.dressed(look, "back")
          }
        }
      }
    }
    .tint(look.tone("accent"))
  }
}

/// A VIEW, drawn: each kind its SwiftUI, each part dressed by its role
struct Node: View {
  let view: ViewNode
  let look: LookSpec
  let dialogue: Dialogue
  let refs: Bool

  var body: some View {
    switch view {
    case .empty: EmptyView()
    case .text(let s): Text(s).dressed(look, "text")
    case .heading(let level, let s):
      Text(s).dressed(look, "heading", "heading-\(min(max(level, 1), 3))").accessibilityAddTraits(.isHeader)
    case .notice(let level, let s):
      Text(s).frame(maxWidth: .infinity, alignment: .leading).dressed(look, "notice", "notice-\(level)")
    case .fields(let rows):
      Grid(alignment: .leading, horizontalSpacing: look.space, verticalSpacing: look.spacing("fields")) {
        ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
          GridRow {
            Text(row.0).dressed(look, "label")
            Node(view: row.1, look: look, dialogue: dialogue, refs: refs)
          }
        }
      }.dressed(look, "fields")
    case .table(let columns, let rows):
      ScrollView(.horizontal) {
        Grid(alignment: .leading, horizontalSpacing: look.space * 2, verticalSpacing: look.space) {
          GridRow { ForEach(columns, id: \.self) { Text($0).dressed(look, "label") } }
          Divider()
          ForEach(Array(rows.enumerated()), id: \.offset) { _, r in
            GridRow {
              ForEach(Array(r.enumerated()), id: \.offset) { _, c in
                Node(view: c, look: look, dialogue: dialogue, refs: refs)
              }
            }
          }
        }
      }.dressed(look, "table")
    case .items(let xs):
      VStack(alignment: .leading, spacing: look.spacing("items")) {
        ForEach(Array(xs.enumerated()), id: \.offset) { _, x in
          Node(view: x, look: look, dialogue: dialogue, refs: refs)
        }
      }.dressed(look, "items")
    case .group(let title, let children):
      let rule = look.rule("group")
      VStack(alignment: .leading, spacing: look.spacing("group")) {
        if let t = title { Text(t).dressed(look, "group-title") }
        Flow(row: rule.row, spacing: look.spacing("group")) {
          ForEach(Array(children.enumerated()), id: \.offset) { _, c in
            Node(view: c, look: look, dialogue: dialogue, refs: refs)
          }
        }
      }.dressed(look, "group")
    case .ref(let key, let label):
      if refs {
        Button(label) { dialogue.act("ref", ["key": [key]]) }.buttonStyle(.plain).dressed(look, "ref")
      } else {
        Text(label).dressed(look, "ref")
      }
    case .link(let label, let url):
      if let u = URL(string: url) { Link(label, destination: u).dressed(look, "link") }
      else { Text(label).dressed(look, "link") }
    case .datum(let text): Text(text).textSelection(.enabled).dressed(look, "datum")
    case .masked(let hint): Text(hint).dressed(look, "masked")
    case .marked(let mark, let inner):
      Node(view: inner, look: look, dialogue: dialogue, refs: refs).dressed(look, "mark", "mark-\(mark)")
    case .picture(let p): PictureView(picture: p, look: look).dressed(look, "picture")
    }
  }
}

/// children in a column, or in a row that wraps
struct Flow<Content: View>: View {
  let row: Bool
  let spacing: CGFloat
  @ViewBuilder let content: () -> Content
  var body: some View {
    if row {
      HStack(alignment: .top, spacing: spacing) { content() }
    } else {
      VStack(alignment: .leading, spacing: spacing) { content() }
    }
  }
}

/// A PICTURE: boxes, bands and labels on a canvas of the program's size, scaled to the width there is
struct PictureView: View {
  let picture: Picture
  let look: LookSpec

  var body: some View {
    Canvas { ctx, size in
      let k = min(size.width / picture.width, size.height / picture.height)
      ctx.scaleBy(x: k, y: k)
      for s in picture.shapes {
        switch s {
        case let .box(x, y, w, h, mark, _):
          ctx.fill(Path(CGRect(x: x, y: y, width: w, height: h)), with: .color(look.markColor(mark)))
        case let .band(x0, y0, x1, y1, t, mark, _):
          let h = t / 2, mx = (x0 + x1) / 2
          var p = Path()
          p.move(to: CGPoint(x: x0, y: y0 - h))
          p.addCurve(to: CGPoint(x: x1, y: y1 - h), control1: CGPoint(x: mx, y: y0 - h),
            control2: CGPoint(x: mx, y: y1 - h))
          p.addLine(to: CGPoint(x: x1, y: y1 + h))
          p.addCurve(to: CGPoint(x: x0, y: y0 + h), control1: CGPoint(x: mx, y: y1 + h),
            control2: CGPoint(x: mx, y: y0 + h))
          p.closeSubpath()
          ctx.fill(p, with: .color(look.markColor(mark).opacity(0.5)))
        case let .label(x, y, text, anchor):
          let a: UnitPoint = anchor == "middle" ? .bottom : anchor == "end" ? .bottomTrailing : .bottomLeading
          ctx.draw(Text(text).font(.system(size: look.size * 0.8 / k)).foregroundStyle(look.tone("fg") ?? .primary),
            at: CGPoint(x: x, y: y), anchor: a)
        }
      }
    }
    .aspectRatio(picture.width / picture.height, contentMode: .fit)
    .accessibilityLabel(picture.text)
  }
}
