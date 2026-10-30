# face-mobile — the dialogue on a phone: an iPhone (Scala Native, SwiftUI) and Android (Scala on ART, Compose)

Status: implemented — iOS 2026-10-29/30, Android 2026-10-30; every example played by taps on the iPhone simulator
(`ShellUITests`) and on the Android emulator (`ShellTest`). Asked by the operator: «сделать поддержку в okay-faces для
iOS», then «Сделаешь для андроида?» — a Scala program under a native shell, not a web view.

The module is `mobile` (`okay-faces-mobile`, package `okay.faces.mobile`): the screen, the form's lowering, the look,
the device — every phone's. Each phone has a shell of its own that knows no application: `ios/` (Swift) and
`android/` (Kotlin). The iOS shell is below as it was built first; Android is under "Android".

## The cut

A face is a loop over `Step`: show the view with its actions, read a `Chosen`, resume. On iOS the loop is cut in two
at the one place a cut costs nothing — between the step machine and the drawing:

- **Scala Native** (`mobile` on Native, a static library per application): the PROGRAM, its handlers, its
  `Session` (history, told views, events). It knows no UIKit. What it gives the shell is a SCREEN as JSON, and what it
  takes is a CHOICE as JSON — the REST face's wire, without HTTP, through eight C functions.
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

## The C interface (`mobile/native`; the header `ios/swift/Sources/COkayFaces/okay_faces.h`)

```c
int ScalaNativeInit(void);                      // once, before anything (Scala Native's own)
char* okay_faces_open(const char* app);         // the application by its class name; the first screen
char* okay_faces_act(const char* choice);       // {"action": name, "form": {"a.b": ["v"], ...}}; the next screen
char* okay_faces_back(void);                    // the step before, when the screen said `back`
char* okay_faces_screen(void);                  // the screen now: after `moved`, what the event moved it to
char* okay_faces_look(void);                    // the application's look
void okay_faces_on_device(char* (*device)(const char*));  // the phone: an operation in, its answer out (malloc'd)
void okay_faces_on_moved(void (*moved)(void));  // told when an event moved the dialogue on by itself
void okay_faces_free(char* s);                  // every string returned is the caller's, freed here
```

One dialogue a process — an application has one. An application is a class extending `MobileApp` (reflectively
instantiable, Scala Native's `@EnableReflectiveInstantiation`): `def dialogue: MobileDialogue`, usually
`MobileFace(program, look)`. A thread of the shell calls them one at a time; `moved` may come from any thread, and the
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

## The device (2026-10-30)

What a program asks of the phone is an effect, `Device`, its operations data with their answers: `Fetch` (the
network; a failure is status 0 and the reason), `Load`/`Save` (UserDefaults), `Secret`/`Hide` (the keychain), `Now`,
`Notify` (a notification, at once or after seconds; whether the person allows them), `Open` (a link, by the system).
The words are `okay.faces.mobile.phone` (`fetch`, `load`, `save`, `forget`, `secret`, `hide`, `now`, `remind`, `open`);
a program on a phone is `A ! Device.Phone` — `Row[*[Pure] + *[Device] + *[Face], X]` in Scala 3 — and
`MobileFace.phone(program, device)` its dialogue.

DECIDED: every operation is answered IN PLACE, by a port — one call, JSON in and JSON out (`Device.Port`). The face
handles `Face` into steps as ever, and runs each step's rest with `Device` handled by the port (`MobileFace.onDevice`),
so a stateful handler stays inside the program and the device is the world outside, as specs/face-core.md asks. On
an iPhone the port is the shell's: `okay_faces_on_device(char* (*)(const char*))` registers a C function, which the
library calls with an operation and whose answer — made by `malloc` (`strdup`) — it frees. Swift answers it with
URLSession, UserDefaults, the keychain, UserNotifications, `UIApplication.open` (`DeviceBridge.swift`). On the JVM and
in tests the port is `Device.Memory` (maps, a network function, a clock, what was notified and opened).

An operation may wait — the network does — so the library is never called on the main thread: the shell has one
thread of its own for it (`ScalaThread`: Scala Native started and called there alone, so its collector sees one
stack), and the screen is published on the main thread after; a step at work shows a progress mark in the bar.

The example is `NotesApp`: notes kept between runs, okay-faces' repository fetched from GitHub's API, a reminder
notified, the site opened — played by `ShellUITests.testNotesOnTheDevice` on the simulator, the app terminated and
launched again in between.

## As iOS does it (2026-10-30)

- NIGHT: the look goes with its palette at night (`MobileLook.dark`: the same style and words on `Theme.dark` when the
  look is on `Theme.light`; a look of its own tokens keeps them); the shell takes the one the phone is set to.
- SIZE: the look's sizes times what the person reads at (Dynamic Type, the system's measure of body text).
- DEPTH: a step one may go back from (`Step.Options.returnable`, the screen's `back`) is pushed as iOS goes deeper,
  and back is the edge of the screen or the bar's chevron, which ask the library's `back`. A step whose title is a
  page already in the stack goes back to that page, as it is now — a list updated by its own form is not a new page,
  a note deleted from its page returns to the list. A step with nothing behind starts the stack again.
- KEPT: `MobileFace.durable(d, device, look)` — the points a `Durable` program passes are kept on the phone and the next
  run starts at the last one; iOS may end an app in the background at any time, and the person finds it as they left
  it. A point that no longer reads starts the program afresh.
- A tap felt (a light haptic), the keyboard put away when a choice is sent, a page pulled down asked again.

## Building an application

```sh
ios/build.sh [sbt project] [name] [out dir]   # the app's Scala for the simulator and a device, one XCFramework
cd ios/example/Shell && xcodegen               # the examples' Xcode project: OkayFaces, the XCFramework, -lc++
```

`ios/build.sh` links Scala Native twice — `arm64-apple-ios17.0-simulator` and `arm64-apple-ios17.0` (`OKAY_IOS_TARGET`,
against the SDK `xcrun` names), each from a clean slate (the other target's objects and archive removed first) — and
puts both into `<name>.xcframework`; Xcode takes the slice its build is for, so a project links the one framework, no
search paths. The library is linked for size (`Mode.releaseSize`; LTO is not used: its objects are bitcode, which an
XCFramework does not take). Measured 2026-10-30, the examples' app for a device, Release: 5.07 MB, 3.43 MB stripped
as the App Store ships it (3.83 MB with `releaseFast`).

ON A PHONE OF ONE'S OWN: open `Shell.xcodeproj`, choose a team under Signing & Capabilities (a free Apple ID will do
for one's own phone), connect the iPhone with Developer Mode on, run. Without signing, a device build is checked by
`xcodebuild -sdk iphoneos -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build`.

Tests: the screen and the lowering on the JVM (`MobileFaceSuite`), the C functions on the Mac host (a native test),
the shell by `xcodebuild ... test` on the simulator (`ShellUITests`).

## Android (2026-10-30)

DECIDED: on Android the program is NOT Scala Native. Android's runtime runs JVM bytecode, so the application's Scala
— its jar, okay-faces', okay-simple's, Scala's own — is dexed into the app like any library, and the shell calls it
in Java's terms: `Shell.open(app)`, `Opened.screen/act/back/look/onMoved`, `Shell.device(phone)` (`mobile/jvm`). No
JNI, no NDK, no second build of the program. The JSON between them is the same as on an iPhone, so the screen, the
lowering and the device are one code; only the drawing differs.

- `android/okayfaces` (a library): `OkayFacesView(app)` in Compose — `Model.kt` reads the screen as `Model.swift`
  does, `Look.kt` dresses parts by role (sizes in sp, so the phone's font size scales them; the night palette when the
  system is dark), `FaceView.kt` and `Forms.kt` draw as the iOS face draws: cards on the page, one main button,
  filled fields, rows with lines, a board's cells square. `Dialogue.kt` keeps the same stack (a returnable step goes
  deeper, a page already there is returned to) on a thread of its own; the system's back — the gesture or the button
  — takes the step back (`BackHandler`), pages slide.
- `DeviceBridge.kt` answers `Device`: HttpURLConnection, SharedPreferences, secrets by AES-GCM under a key kept in
  the Android Keystore, notifications (asking `POST_NOTIFICATIONS` once, through the activity), an Intent to view.
- `android/shell`: the examples' app, `-e app <class>`, `--ez reset true`; `ShellTest` plays the same four scenarios
  as the iPhone's UI tests on the emulator: the list kept across runs, at night and at a large font size; noughts;
  notes kept, GitHub's API fetched, the reminder in the system's shade; a note's page and back.
- `android/build.sh` puts the examples' runtime classpath into `android/libs`; `android/test.sh` builds, runs the
  tests on the device adb sees and pulls the screens (`/sdcard/Download/okay-faces`).

## Open

- Pictures are drawn by SwiftUI's `Canvas`; a QR code is a picture, so it comes for free.
