# face-ios — the dialogue on an iPhone: the program in Scala Native, the screen in SwiftUI

Status: implemented 2026-10-29 — both examples played by taps on the iPhone simulator (`ShellUITests`). Asked by the
operator: «сделать поддержку в okay-faces для iOS» — the way of a Scala program as a native library under a Swift shell
(not a web view, not a JVM on the phone).

## The cut

A face is a loop over `Step`: show the view with its actions, read a `Chosen`, resume. On iOS the loop is cut in two
at the one place a cut costs nothing — between the step machine and the drawing:

- **Scala Native** (`ios`, a static library `libokay-app.a` per application): the PROGRAM, its handlers, its
  `Session` (history, told views, events). It knows no UIKit. What it gives the shell is a SCREEN as JSON, and what it
  takes is a CHOICE as JSON — the REST face's wire, without HTTP, through seven C functions.
- **Swift** (`ios/swift`, the package `OkayFaces`): a SwiftUI face that knows no application, as the remote Laminar
  face knows none: it draws whatever `View` comes, draws a form from whatever fields come, and sends back what was
  typed. It interprets the `Look` (tones, rules by role, words) as the Swing face does: never a colour or a word of its
  own.

So the whole of an application is Scala; Swift is the same few files for every application, and Xcode's project is
the shell's: the library linked, `OkayFaces` added, one line naming the application.

DECIDED: the form is LOWERED in Scala, not read from `Schema` in Swift. The shell gets fields by DOTTED NAME — the
HTML face's names (`due`, `address.city`, `kind.type`, `items.0.title`) — with a kind each, and sends back the
values by those names; Scala builds the arguments with core's `FormJson.collect`, exactly as the HTML face's posted
form is read, and decodes them with the action's shape. The schema's walk is then written once, in Scala, tested on
the JVM; Swift only draws a flat list of kinds.

## The C interface (`ios/native`; the header `ios/swift/Sources/COkayFaces/okay_faces.h`)

```c
int ScalaNativeInit(void);                      // once, before anything (Scala Native's own)
char* okay_faces_open(const char* app);         // the application by its class name; the first screen
char* okay_faces_act(const char* choice);       // {"action": name, "form": {"a.b": ["v"], ...}}; the next screen
char* okay_faces_back(void);                    // the step before, when the screen said `back`
char* okay_faces_screen(void);                  // the screen now: after `moved`, what the event moved it to
char* okay_faces_look(void);                    // the application's look
void okay_faces_on_moved(void (*moved)(void));  // told when an event moved the dialogue on by itself
void okay_faces_free(char* s);                  // every string returned is the caller's, freed here
```

One dialogue a process — an application has one. An application is a class extending `IosApp` (reflectively
instantiable, Scala Native's `@EnableReflectiveInstantiation`): `def dialogue: IosDialogue`, usually
`IosFace(program, look)`. A thread of the shell calls them one at a time; `moved` may come from any thread, and the
shell goes to its main thread before it asks again.

## The screen

```json
{"title": "To do", "told": [view...], "view": view, "actions": [action...], "problems": [{"path": [..], "message": ..}],
 "back": true, "done": false, "listening": false}
```

`view` and `told` are `ViewJson`. `problems` are the last choice's, when it was rejected (the step stays). An action:

```json
{"name": "add", "label": "Add", "doc": "...", "fields": [field...], "ref": false}
```

`fields` empty is a button. A field: `{"name": "due", "kind": k, "label": .., "doc": .., "required": bool,
"default": "..", "more": bool}` with the kinds `text`, `long` (a text area: `Action.long`), `secret`, `integer`,
`number`, `bool`, `date` (`yyyy-mm-dd`), `choice` (`options`), `lines` (a list of leaves, one per line), `group`
(`fields`, a record within), `oneof` (`cases`: `[{"name", "fields"}]`, the case chosen sent as `<name>.type`), `many`
(`item`: the fields of one item with `#` for its index, `min`; the shell adds slots). `more` fields are drawn under the
look's `options` word, closed. An action named `ref` with one field `key` makes every `View.Ref` live: a tap is that
action with the ref's key.

## The look

`okay_faces_look` gives `{"theme": {"palette": {tone: "#rrggbb"}, "font", "size", "space", "measure"}, "rules":
{role-class: rule}, "words": {...}}` — a rule as `Rule`'s fields, by the role's most specific name (`heading-1`,
`mark-done`). The shell maps tones to colours, `size` to the base size's multiple, `pad`/`gap` to the unit of space,
`flow` to a row or a column.

## Building an application

```sh
sbt -Dplatforms=native "iosExample/nativeLink"            # the example's library for the simulator
ios/build.sh <sbt project> <out dir> [device|simulator]   # a library per architecture, the header beside it
```

The library's target is `arm64-apple-ios17.0-simulator` or `arm64-apple-ios17.0` (`OKAY_IOS_TARGET`), compiled
against the SDK `xcrun` names. Xcode links it with `-lc++`. Tests: the screen and the lowering on the JVM
(`IosFaceSuite`), the C functions on the Mac host (a native test), the shell by `xcodebuild` on the simulator.

## Open

- An Android face over the same seven functions (JNI): the screen and the lowering are not iOS's.
- Pictures are drawn by SwiftUI's `Canvas`; a QR code is a picture, so it comes for free.
- `Durable` points kept by the shell (`UserDefaults`) so an application killed in the background starts where it was.
