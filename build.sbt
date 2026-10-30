/** the libraries' Scala 3: the LTS, as okay-simple's; and Scala 2.13 — one source for both */
val scala3 = "3.3.8"
val scala2 = "2.13.18"
import sbtcrossproject.{CrossProject, CrossType}
import Platforms._

ThisBuild / organization := "io.github.okay-effects"
ThisBuild / organizationName := "okay-effects"
ThisBuild / homepage := Some(url("https://github.com/okay-effects/okay-faces"))
ThisBuild / scmInfo := Some(ScmInfo(url("https://github.com/okay-effects/okay-faces"),
    "scm:git:https://github.com/okay-effects/okay-faces.git"))
ThisBuild / developers := List(Developer("sergey-scherbina", "Sergiy Shcherbyna", "sergey.scherbina@gmail.com",
    url("https://github.com/sergey-scherbina")))
ThisBuild / startYear := Some(2026)
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / version := "0.1.1-SNAPSHOT"
ThisBuild / licenses := List("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt"))
ThisBuild / scalaVersion := scala3
/** RELEASES, ON GITHUB PAGES: `sbt -Dplatforms=jvm,js publishPages` writes every published module, for both
 * compilers and every platform asked, as a Maven repository into `target/pages-maven/`; that directory goes to the
 * `gh-pages` branch under `maven/`, served at https://okay-effects.github.io/okay-faces/maven (RELEASING.md) */
val pagesRepository = settingKey[File]("the Maven repository a release is written to, for GitHub Pages")
ThisBuild / pagesRepository := (ThisBuild / baseDirectory).value / "target" / "pages-maven"
ThisBuild / publishTo := Some(MavenCache("pages", (ThisBuild / pagesRepository).value))
/** no API documentation jars, as okay-simple: the sources jars are published, which IDEs read for the documentation */
ThisBuild / Compile / packageDoc / publishArtifact := false
/** okay-simple, okay-http and okay-mcp, each from its Pages repository */
ThisBuild / resolvers += "okay-effects" at "https://okay-effects.github.io/okay-simple/maven"
ThisBuild / resolvers ++= Seq("okay-http" at "https://okay-effects.github.io/okay-http/maven",
  "okay-mcp" at "https://okay-effects.github.io/okay-mcp/maven")
val okayEffects = "0.1.1"

def major(v: String): String = if (v.startsWith("3")) "3" else "2"

/** one source for both compilers, and each its own convenience in scala-2/ and scala-3/ */
def crossBuilt: Seq[Def.Setting[?]] = Seq(
  crossScalaVersions := Seq(scala3, scala2),
  scalacOptions ++= (if (scalaVersion.value.startsWith("3")) Seq("-deprecation", "-feature")
                     else Seq("-deprecation", "-feature", "-Xlint")),
  libraryDependencies += "org.scalameta" %%% "munit" % "1.2.3" % Test)

/** THE PLATFORMS BUILT: the JVM always; Scala.js and Scala Native when asked —
 * `sbt -Dplatforms=jvm,js ...`, or the environment's `OKAY_PLATFORMS=js,native` */
val platformsAsked: Set[String] =
  (sys.props.get("platforms") orElse sys.env.get("OKAY_PLATFORMS")).getOrElse("jvm").split(',').map(
    _.trim.toLowerCase).toSet + "jvm"
def platformsOf(can: Set[String]): Seq[sbtcrossproject.Platform] = Seq[sbtcrossproject.Platform](JVMPlatform) ++
  (if (platformsAsked("js") && can("js")) Seq(JSPlatform) else Nil) ++
  (if (platformsAsked("native") && can("native")) Seq(NativePlatform) else Nil)
def crossBoth(id: String, dir: String, can: Set[String] = Set("js", "native")): CrossProject =
  CrossProject(id, file(dir))(platformsOf(can): _*).crossType(CrossType.Pure)
    .settings(crossBuilt).jvmConfigure(_.withId(id)).onJS(_.withId(id + "JS")).onNative(_.withId(id + "Native"))

/** FACE-CORE: View, Action, the effect Face, the step machine — over okay-simple's `shape` (Shape, Schema) and `json`
 * (Json) */
lazy val core = crossBoth("core", "core").settings(name := "okay-faces",
  libraryDependencies += "io.github.okay-effects" %%% "okay-effects-describe" % okayEffects,
  libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-effects" % okayEffects,
    "io.github.okay-effects" %%% "okay-effects-shape" % okayEffects,
    "io.github.okay-effects" %%% "okay-effects-tagless" % okayEffects,
    "io.github.okay-effects" %%% "okay-effects-machine" % okayEffects % Test))
  // a QR code read back by a reader of its own (ZXing): the JVM's tests alone
  .jvmSettings(libraryDependencies += "com.google.zxing" % "core" % "3.5.3" % Test)

/** THE TEST FACE: a step machine driven by a script of choices, every view collected, back as an entry */
lazy val test = crossBoth("test", "test").dependsOn(core).settings(name := "okay-faces-test",
  libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-effects-machine" % okayEffects % Test,
    "io.github.okay-effects" %%% "okay-effects-free" % okayEffects % Test))

/** THE TERMINAL FACE: a Step loop in Console, the person as a handler of Fields */
lazy val term = crossBoth("term", "term").dependsOn(core).settings(name := "okay-faces-term",
  libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-effects-io" % okayEffects,
    "io.github.okay-effects" %%% "okay-effects-machine" % okayEffects % Test,
    "io.github.okay-effects" %%% "okay-effects-free" % okayEffects % Test))

/** THE SWING FACE, the JVM: a Step machine as a panel, forms from the shape, a window on the event dispatch thread */
lazy val swing = (project in file("swing")).dependsOn(core.jvm)
  .settings(crossBuilt, name := "okay-faces-swing",
    libraryDependencies += "io.github.okay-effects" %% "okay-effects-machine" % okayEffects % Test)

/** THE MCP FACE: the dialogue as tools for an agent, over okay-mcp — the actions a person would see, as tools */
val okayMcp = "0.1.0"
val okayHttp = "0.1.0"
lazy val mcp = crossBoth("mcp", "mcp").dependsOn(core).settings(name := "okay-faces-mcp",
  libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-mcp" % okayMcp,
    "io.github.okay-effects" %%% "okay-http" % okayHttp,
    "io.github.okay-effects" %%% "okay-effects-machine" % okayEffects % Test))

/** THE REST FACE and THE HTML FACE, over okay-http: the dialogue as a JSON API with links, and as pages from the server
 */
lazy val rest = crossBoth("rest", "rest").dependsOn(core).settings(name := "okay-faces-rest",
  // the server's words on the JVM (`Web`, `Linked`), told impossible in the browser
  Compile / unmanagedSourceDirectories +=
    baseDirectory.value.getParentFile / "src" / "main" / ("scala-" + (if (baseDirectory.value.getName == ".jvm")
                                                                                                              "jvm"
                                                                                                            else "js")),
  libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-http" % okayHttp,
    "io.github.okay-effects" %%% "okay-http-rest" % okayHttp,
    "io.github.okay-effects" %%% "okay-effects-machine" % okayEffects % Test))
lazy val html = crossBoth("html", "html").dependsOn(core, rest).settings(name := "okay-faces-html",
  libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-http" % okayHttp,
    "io.github.okay-effects" %%% "okay-http-rest" % okayHttp,
    "io.github.okay-effects" %%% "okay-effects-machine" % okayEffects % Test))

/** EXAMPLES: noughts and crosses — one program, every face. The game is shared source; the JVM's mains (a window, the
 * terminal, an agent, a server) in `scala-jvm`, the page with the game in it in `scala-js` (`examplesJS`, when js is
 * asked:
 * `sbt -Dplatforms=js examplesJS/fullLinkJS` links it); the THIN page, knowing no game, is its own script,
 * `examplesRemoteJS`
 * (`examples/remote`); `sbt "examples/runMain okay.faces.examples.NoughtsWebRemote"` serves both */
lazy val examples = crossBoth("examples", "examples", can = Set("js"))
  .settings(name := "okay-faces-examples", publish / skip := true,
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-effects-machine" % okayEffects,
      "io.github.okay-effects" %%% "okay-effects-free" % okayEffects))
  .jvmConfigure(_.dependsOn(core.jvm, test.jvm, term.jvm, swing, rest.jvm, html.jvm, mcp.jvm).settings(
      Compile / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "src" / "main" / "scala-jvm",
      Test / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "src" / "test" / "scala-jvm"))
  .onJS(_.dependsOn(core.js, laminar.js, rest.js).settings(scalaJSUseMainModuleInitializer := true,
      Compile / mainClass := Some("okay.faces.examples.NoughtsLocal"), // this script the game's; the list's is its own
      Compile / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "src" / "main" / "scala-js",
      // the pages tested under jsdom, as the Laminar face is: `sbt -Dplatforms=js examplesJS/test`
      Test / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "src" / "test" / "scala-js",
      Test / jsEnv := new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv(
        org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv.Config()
          .withEnv(Map("NODE_PATH" -> sys.env.getOrElse("NODE_PATH", "/opt/homebrew/lib/node_modules")))),
      libraryDependencies ++= Seq("com.raquo" %%% "laminar" % "17.2.1",
        "io.github.okay-effects" %%% "okay-http" % okayHttp)))
/** THE LIST'S PAGE: its own script, `examples/todo/.js`, the same sources — one page, one script, no dispatch */
lazy val examplesTodo = CrossProject("examplesTodo", file("examples/todo"))(JSPlatform).crossType(CrossType.Pure)
  .settings(crossBuilt, name := "okay-faces-examples-todo", publish / skip := true,
    scalaJSUseMainModuleInitializer := true, Compile / mainClass := Some("okay.faces.examples.TodoLocal"))
  .jsConfigure(p =>
    if (platformsAsked("js")) p.withId("examplesTodoJS").dependsOn(examples.js) else p.withId("examplesTodoJS"))
lazy val examplesRemote = CrossProject("examplesRemote", file("examples/remote"))(JSPlatform).crossType(CrossType.Pure)
  .settings(crossBuilt, name := "okay-faces-examples-remote", publish / skip := true,
    scalaJSUseMainModuleInitializer := true,
    Compile / mainClass := Some(
      "okay.faces.laminar.RemotePage"), // the library's thin page, no source of the example's own
    libraryDependencies ++= Seq("com.raquo" %%% "laminar" % "17.2.1",
      "io.github.okay-effects" %%% "okay-http" % okayHttp))
  .jsConfigure(p =>
    if (platformsAsked("js")) p.withId("examplesRemoteJS").dependsOn(laminar.js) else p.withId("examplesRemoteJS"))

/** THE THIN PAGE WITH NO LIBRARY: the DOM remote face's script alone (`okay.faces.dom.DomRemotePage`), as
 * `examplesRemote` is the Laminar one's — either serves a program behind the REST face, the JVM's or Cloudflare's */
lazy val examplesRemoteDom = CrossProject("examplesRemoteDom", file("examples/remote-dom"))(JSPlatform)
  .crossType(CrossType.Pure)
  .settings(crossBuilt, name := "okay-faces-examples-remote-dom", publish / skip := true,
    scalaJSUseMainModuleInitializer := true, Compile / mainClass := Some("okay.faces.dom.DomRemotePage"))
  .jsConfigure(p =>
    if (platformsAsked("js")) p.withId("examplesRemoteDomJS").dependsOn(domFace.js)
    else p.withId("examplesRemoteDomJS"))

/** THE LAMINAR FACE, Scala.js only: a Step machine as a Laminar element — the view drawn, the actions buttons and
 * forms,
 * a click is `resume`; and THE REMOTE FACE, the same page over a dialogue behind the REST face, through okay-http's
 * `Browser.fetch`. Built and tested when js is asked: `sbt -Dplatforms=js laminarJS/test`, under jsdom */
lazy val laminar = CrossProject("laminar", file("web/laminar"))(JSPlatform).crossType(CrossType.Pure)
  .settings(crossBuilt, name := "okay-faces-laminar",
    libraryDependencies ++= Seq("com.raquo" %%% "laminar" % "17.2.1",
      "io.github.okay-effects" %%% "okay-effects-async" % okayEffects,
      "io.github.okay-effects" %%% "okay-http" % okayHttp,
      "io.github.okay-effects" %%% "okay-effects-machine" % okayEffects % Test),
    // jsdom for the tests: from NODE_PATH, else the global npm root on this machine; `npm install jsdom` here works too
    Test / jsEnv := new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv(
      org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv.Config()
        .withEnv(Map("NODE_PATH" -> sys.env.getOrElse("NODE_PATH", "/opt/homebrew/lib/node_modules")))))
  // on core's JS project only when js is asked: core has no JS platform otherwise; the REST face in the tests, as the
  // remote face's server
  .jsConfigure(p =>
    if (platformsAsked("js")) p.withId("laminarJS").dependsOn(core.js, rest.js % "test->compile")
    else p.withId("laminarJS"))

/** THE DOM FACES, Scala.js only: the Laminar faces' — the local one's step machine, the remote one's page over the REST
 * face — and their markup by the DOM's own calls, no library: the same classes, so a look dresses them the same and
 * only the handler changes. Its tests: the Laminar face's, and the
 * two faces' markup compared step by step (Laminar in the tests only). `sbt -Dplatforms=js domJS/test`, under jsdom */
lazy val domFace = CrossProject("dom", file("web/dom"))(JSPlatform).crossType(CrossType.Pure)
  .settings(crossBuilt, name := "okay-faces-dom",
    libraryDependencies ++= Seq("org.scala-js" %%% "scalajs-dom" % "2.8.0",
      "io.github.okay-effects" %%% "okay-effects-async" % okayEffects,
      "io.github.okay-effects" %%% "okay-http" % okayHttp,
      // the thin page's script runs its own programs: on the machine
      "io.github.okay-effects" %%% "okay-effects-machine" % okayEffects),
    Test / jsEnv := new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv(
      org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv.Config()
        .withEnv(Map("NODE_PATH" -> sys.env.getOrElse("NODE_PATH", "/opt/homebrew/lib/node_modules")))))
  .jsConfigure(p =>
    if (platformsAsked("js")) p.withId("domJS").dependsOn(core.js, laminar.js % "test->compile", rest.js % "test->compile")
    else p.withId("domJS"))

/** CLOUDFLARE WORKERS, Scala.js only: the server faces (HTML, REST) hosted by a Worker, each session a Durable Object,
 * its journal played again when the object wakes — a way to deploy the same program, not a part of it
 * (specs/cloudflare.md). `sbt -Dplatforms=js cloudflareJS/compile`; the example `examplesCloudflareJS` */
lazy val cloudflare = crossBoth("cloudflare", "web/cloudflare", can = Set("js"))
  .dependsOn(core, rest, html, mcp).settings(name := "okay-faces-cloudflare",
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-http" % okayHttp,
      "io.github.okay-effects" %%% "okay-effects-machine" % okayEffects % Test))
  // the Worker and the Durable Object, Scala.js's alone; `Hosting`, the session's program, everywhere
  .onJS(_.settings(
    Compile / unmanagedSourceDirectories += baseDirectory.value.getParentFile / "src" / "main" / "scala-js"))
/** THE LIST ON CLOUDFLARE: the to-do example's Cloudflare main — its pages and its API, each session a Durable Object;
 * `sbt -Dplatforms=js examplesCloudflareJS/fullLinkJS`, then `examples/cloudflare/deploy.sh` (wrangler) */
lazy val examplesCloudflare = CrossProject("examplesCloudflare", file("examples/cloudflare"))(JSPlatform)
  .crossType(CrossType.Pure)
  .settings(crossBuilt, name := "okay-faces-examples-cloudflare", publish / skip := true,
    scalaJSUseMainModuleInitializer := true, Compile / mainClass := Some("okay.faces.examples.TodoCloudflare"),
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.ESModule)),
    libraryDependencies += "io.github.okay-effects" %%% "okay-effects-machine" % okayEffects)
  .jsConfigure(p =>
    if (platformsAsked("js")) p.withId("examplesCloudflareJS").dependsOn(cloudflare.js, examples.js)
    else p.withId("examplesCloudflareJS"))

/** AIRSTREAM'S STREAMS AND OKAY'S, both ways, a Var as a State's handler — no Face: a Laminar team may take it alone */
lazy val airstream = CrossProject("airstream", file("web/airstream"))(JSPlatform).crossType(CrossType.Pure)
  .settings(crossBuilt, name := "okay-faces-airstream",
    libraryDependencies ++= Seq("com.raquo" %%% "airstream" % "17.2.1",
      "io.github.okay-effects" %%% "okay-effects-stream" % okayEffects,
      "io.github.okay-effects" %%% "okay-effects-async" % okayEffects,
      "io.github.okay-effects" %%% "okay-effects-machine" % okayEffects),
    Test / jsEnv := new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv(
      org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv.Config()
        .withEnv(Map("NODE_PATH" -> sys.env.getOrElse("NODE_PATH", "/opt/homebrew/lib/node_modules")))))
  .jsConfigure(_.withId("airstreamJS"))

/** ROUTES AS AN EFFECT: Navigation, handled by Waypoint's Router or by a recording — over the airstream bridge */
lazy val waypoint = CrossProject("waypoint", file("web/waypoint"))(JSPlatform).crossType(CrossType.Pure)
  .settings(crossBuilt, name := "okay-faces-waypoint",
    libraryDependencies ++= Seq("com.raquo" %%% "waypoint" % "9.0.0", "com.raquo" %%% "laminar" % "17.2.1"),
    Test / jsEnv := new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv(
      org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv.Config()
        .withEnv(Map("NODE_PATH" -> sys.env.getOrElse("NODE_PATH", "/opt/homebrew/lib/node_modules")))))
  .jsConfigure(p =>
    if (platformsAsked("js")) p.withId("waypointJS").dependsOn(core.js) else p.withId("waypointJS")).dependsOn(
    airstream)

/** THE DOCUMENTATION'S CODE, CHECKED (as okay-simple does): each ```scala block of the README, in turn in one class,
 * compiled by Scala 3; an instance made in a test, so a block's `assert`s run. `sbt docsCheck/test` */
def docBlocks = Def.task {
  val root = (ThisBuild / baseDirectory).value
  val out = (Test / sourceManaged).value / "docs"
  val Block = "(?s)```scala\n(.*?)```".r
  val pages = Seq(root / "README.md").flatMap { md =>
    val blocks = Block.findAllMatchIn(IO.read(md)).map(_.group(1)).toList
    if (blocks.isEmpty) None else Some(md.getName.replaceAll("[^A-Za-z0-9]", "_") -> blocks)
  }
  val classes = pages.map { case (name, blocks) =>
    val f = out / s"$name.scala"
    IO.write(f, s"package docs.check\n\nclass $name {\n" + blocks.mkString("\n") + "\n}\n"); f
  }
  val suite = out / "DocsSuite.scala"
  IO.write(suite, "package docs.check\n\nclass DocsSuite extends munit.FunSuite {\n" + pages.map { case (name, _) =>
      "  test(\"" + name + "\") { assert(new " + name + "().toString.nonEmpty) }\n"
    }.mkString + "}\n")
  classes :+ suite
}
/** THE PHONES' FACE (specs/mobile.md): the program's screens and choices as JSON text, the form lowered, the look as
 * data, the phone as an effect (`Device`) — shared, tested on the JVM. Its shells draw it natively and know no
 * application: on an iPhone the program is Scala Native, a static library of C functions (`native`), drawn by
 * SwiftUI (`ios/swift`); on Android the program is JVM bytecode on ART, drawn by Jetpack Compose (`android/`) */
lazy val mobile = CrossProject("mobile", file("mobile"))(platformsOf(Set("native")): _*).crossType(CrossType.Full)
  .dependsOn(core)
  .settings(crossBuilt, name := "okay-faces-mobile",
    libraryDependencies += "io.github.okay-effects" %%% "okay-effects-machine" % okayEffects % Test)
  .jvmConfigure(_.withId("mobile")).onNative(_.withId("mobileNative"))

/** THE EXAMPLES ON A PHONE: the list, noughts and crosses, notes on the device — `MobileApp`s. `ios/build.sh` links
 * them into a static library for an iPhone (`OKAY_IOS_TARGET`), the Xcode project in `ios/example/Shell` draws them;
 * `android/build.sh` puts their JVM jar into the Android shell (`android/`) */
lazy val mobileExample = CrossProject("mobileExample", file("mobile/example"))(platformsOf(Set("native")): _*)
  .crossType(CrossType.Pure).dependsOn(mobile)
  .settings(name := "okay-faces-mobile-example", publish / skip := true, scalaVersion := scala3,
    crossScalaVersions := Seq(scala3),
    Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "examples" / "src" / "main" / "scala",
    libraryDependencies ++= Seq("io.github.okay-effects" %%% "okay-effects-machine" % okayEffects,
      "io.github.okay-effects" %%% "okay-effects-free" % okayEffects,
      "org.scalameta" %%% "munit" % "1.2.3" % Test))
  .jvmConfigure(_.withId("mobileExample")).onNative(_.withId("mobileExampleNative").settings(IosBuild.library))

lazy val docsCheck = (project in file("docs/check"))
  .dependsOn(core.jvm, test.jvm, term.jvm, swing, mcp.jvm, rest.jvm, html.jvm)
  .settings(name := "okay-faces-docs-check", publish / skip := true, scalaVersion := scala3,
    crossScalaVersions := Seq(scala3), libraryDependencies += "org.scalameta" %% "munit" % "1.2.3" % Test,
    scalacOptions ++= Seq("-deprecation", "-feature"), Test / sourceGenerators += docBlocks.taskValue)

/** every module on every platform asked: the cross-built ones' projects, the web ones' when js is asked */
lazy val root = (project in file(".")).aggregate((Seq[ProjectReference](swing, docsCheck) ++
    Seq(core, test, term, mcp, rest, html, cloudflare, examples, mobile, mobileExample)
      .flatMap(_.projects.values.map(p => p: ProjectReference)) ++
    ((if (platformsAsked("js"))
        Seq[ProjectReference](laminar.js, domFace.js, airstream.js, waypoint.js, examplesTodo.js, examplesRemote.js,
          examplesCloudflare.js, examplesRemoteDom.js)
      else Nil))): _*).settings(name := "okay-faces-root", publish / skip := true, crossScalaVersions := Nil)

/** a release into `target/pages-maven/`, on Scala 3.3 and 2.13 — `sbt -Dplatforms=jvm,js publishPages`: the
 * web faces are built only when js is asked, so a release asks for it */
addCommandAlias("publishPages", "; ++3.3.8 publish ; ++2.13.18 publish ; ++3.3.8")
