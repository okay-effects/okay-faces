// THE FACE: the screen drawn — what was told, the step's view, the problems, the actions — in the application's look.
// `OkayFacesView(app: "my.App")` is the whole of an app's SwiftUI.
import SwiftUI

public struct OkayFacesView: View {
  @StateObject private var dialogue: Dialogue
  @Environment(\.colorScheme) private var scheme
  @Environment(\.dynamicTypeSize) private var typeSize

  public init(app: String) { _dialogue = StateObject(wrappedValue: Dialogue(app: app)) }

  public var body: some View {
    // the look as the phone is set: its palette at night when dark, its sizes as the person reads (Dynamic Type)
    let look = LookSpec(dialogue.lookJSON, dark: scheme == .dark, scale: Self.scale(typeSize))
    // the steps one may go back to, as iOS goes deeper: each pushed, back by the edge of the screen
    let path = Binding<[Int]>(
      get: { Array(dialogue.stack.indices.dropFirst()) },
      set: { p in if p.count < dialogue.stack.count - 1 { dialogue.back() } })
    NavigationStack(path: path) {
      Page(index: 0, look: look, dialogue: dialogue)
        .navigationDestination(for: Int.self) { i in Page(index: i, look: look, dialogue: dialogue) }
    }
    .tint(look.tone("accent"))
  }

  /// how much larger the person reads than the default, by the system's own measure of body text
  static func scale(_ size: DynamicTypeSize) -> Double {
    #if canImport(UIKit)
    let traits = UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory(size))
    return UIFontMetrics(forTextStyle: .body).scaledValue(for: 100, compatibleWith: traits) / 100
    #else
    return 1
    #endif
  }
}

/// ONE STEP'S PAGE: what was told, the step's view, its problems, its actions — the step at `index` of the stack
struct Page: View {
  let index: Int
  let look: LookSpec
  @ObservedObject var dialogue: Dialogue

  var body: some View {
    let screen = dialogue.stack.indices.contains(index) ? dialogue.stack[index] : Screen.empty
    let top = index == dialogue.stack.count - 1
    ScrollViewReader { scroll in
      ScrollView {
        VStack(alignment: .leading, spacing: look.spacing("face")) {
          Color.clear.frame(height: 0).id("top")
          if let e = screen.error { Text(e).dressed(look, "notice", "notice-problem") }
          ForEach(Array(screen.told.enumerated()), id: \.offset) { _, v in
            Node(view: v, look: look, dialogue: dialogue, refs: false).card(look)
              .environment(\.okayInherited, Inherited(color: "muted"))
          }
          Node(view: screen.view, look: look, dialogue: dialogue, refs: screen.refs && top).card(look)
          if !screen.problems.isEmpty {
            VStack(alignment: .leading) { ForEach(screen.problems, id: \.self) { Text($0) } }
              .dressed(look, "problems")
          }
          Actions(actions: screen.actions.filter { $0.name != "ref" }, look: look, dialogue: dialogue)
            .id(dialogue.turn).disabled(!top)
        }
        .padding(.horizontal, look.space * 2).padding(.vertical, look.space)
      }
      .scrollDismissesKeyboard(.interactively)
      // pulled down: the screen asked again — what an event brought, if anything
      .refreshable { dialogue.refresh() }
      // a new step is read from its start: what was told first, then the step
      .onChange(of: dialogue.turn) { withAnimation { scroll.scrollTo("top", anchor: .top) } }
    }
    .background(look.page)
    .navigationBarTitleDisplayMode(.inline)
    .toolbarRole(.editor)
    .toolbarBackground(look.page, for: .navigationBar)
    .toolbarBackground(.visible, for: .navigationBar)
    .toolbar {
      if dialogue.busy && top {
        ToolbarItem(placement: .topBarTrailing) { ProgressView().accessibilityIdentifier("busy") }
      }
    }
  }
}

/// A VIEW, drawn: each kind its SwiftUI, each part dressed by its role
struct Node: View {
  let view: ViewNode
  let look: LookSpec
  let dialogue: Dialogue
  let refs: Bool
  @Environment(\.okayInherited) private var around

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
      // as wide as it needs, in the middle when its rule says so; scrolled across only when it does not fit
      let cell = look.size * 3
      let grid = Grid(alignment: .center, horizontalSpacing: look.space, verticalSpacing: look.space) {
        if columns.contains(where: { !$0.isEmpty }) {
          GridRow { ForEach(Array(columns.enumerated()), id: \.offset) { Text($0.1).dressed(look, "label") } }
          Divider()
        }
        ForEach(Array(rows.enumerated()), id: \.offset) { _, r in
          GridRow {
            ForEach(Array(r.enumerated()), id: \.offset) { _, c in
              Node(view: c, look: look, dialogue: dialogue, refs: refs).frame(minWidth: cell, minHeight: cell)
                .aspectRatio(1, contentMode: .fit)
            }
          }
        }
      }
      ViewThatFits(in: .horizontal) {
        grid
        ScrollView(.horizontal) { grid }
      }.dressed(look, "table")
    case .items(let xs):
      // rows, as iOS lists them: a line between, the whole row the place to tap
      VStack(alignment: .leading, spacing: 0) {
        ForEach(Array(xs.enumerated()), id: \.offset) { i, x in
          if i > 0 { Divider() }
          Node(view: x, look: look, dialogue: dialogue, refs: refs)
            .padding(.vertical, look.space * 1.25).frame(maxWidth: .infinity, alignment: .leading)
        }
      }
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
        Button { dialogue.act("ref", ["key": [key]]) } label: {
          Text(label).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: around.centered ? .center : .leading)
            .contentShape(Rectangle())
        }.buttonStyle(.plain).dressed(look, "ref").accessibilityIdentifier("ref-\(key)")
      } else {
        Text(label).dressed(look, "ref")
      }
    case .link(let label, let url):
      if let u = URL(string: url) { Link(label, destination: u).dressed(look, "link") }
      else { Text(label).dressed(look, "link") }
    case .datum(let text): Text(text).textSelection(.enabled).dressed(look, "datum")
    case .masked(let hint): Text(hint).dressed(look, "masked")
    case .image(let source, let alt): ImageView(source: source, alt: alt).dressed(look, "image")
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

/// AN IMAGE: the image itself when the source is a data: URI (a photo the phone took), else fetched from its address;
/// its words to VoiceOver
struct ImageView: View {
  let source: String
  let alt: String
  var body: some View {
    Group {
      if source.hasPrefix("data:"), let i = source.firstIndex(of: ","),
         let data = Data(base64Encoded: String(source[source.index(after: i)...])), let image = UIImage(data: data) {
        Image(uiImage: image).resizable().scaledToFit()
      } else if let url = URL(string: source) {
        AsyncImage(url: url) { $0.resizable().scaledToFit() } placeholder: { ProgressView() }
      } else {
        Text("[\(alt)]")
      }
    }
    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    .accessibilityLabel(alt)
    .accessibilityIdentifier("image")
  }
}
