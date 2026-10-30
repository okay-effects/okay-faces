# Releasing

Releases are a Maven repository on GitHub Pages: the `gh-pages` branch, its `maven/` directory, served at
https://okay-effects.github.io/okay-faces/maven. No account and no token is needed to use it, only resolvers: this one
and those of what it depends on, okay-simple, okay-http and okay-mcp.

## Using a release

```scala-sbt
resolvers ++= Seq("okay-effects" at "https://okay-effects.github.io/okay-simple/maven",
  "okay-http" at "https://okay-effects.github.io/okay-http/maven",
  "okay-mcp" at "https://okay-effects.github.io/okay-mcp/maven",
  "okay-faces" at "https://okay-effects.github.io/okay-faces/maven")

libraryDependencies ++= Seq(
  "io.github.okay-effects" %% "okay-faces"      % "0.1.0",          // Face, the words, the step machine
  "io.github.okay-effects" %% "okay-faces-term" % "0.1.0",          // and the faces wanted: term, swing, rest, html,
  "io.github.okay-effects" %% "okay-faces-test" % "0.1.0" % Test    // mcp; laminar, airstream, waypoint on Scala.js
)
```

`%%%` for Scala.js and Scala Native. Built on Scala 3.3 LTS and Scala 2.13, for the JVM and Scala.js; for Scala Native
the core and the phones' face (`okay-faces-mobile`, specs/mobile.md: an application's static library for an iPhone; on Android the JVM artifact) — the other
faces' tests do not end there yet.

## Making one

1. On `main`, everything green, on the JVM and Scala.js, by both compilers:

   ```sh
   sbt -Dplatforms=jvm,js clean +test
   ```

2. The version: `ThisBuild / version` in `build.sbt` without `-SNAPSHOT`, committed, tagged (`v0.1.0`).

3. The repository written into `target/pages-maven/`, for both compilers and both platforms — js asked, or the web
   faces are not built:

   ```sh
   rm -rf target/pages-maven
   sbt -Dplatforms=jvm,js publishPages
   OKAY_PLATFORMS=native sbt "++3.3.8 coreNative/publish" "++3.3.8 mobileNative/publish" \
     "++2.13.18 coreNative/publish" "++2.13.18 mobileNative/publish"
   ```

4. Onto `gh-pages`, under `maven/`, beside the releases already there:

   ```sh
   # the first time: git worktree add --orphan -b gh-pages /tmp/okay-faces-pages
   git worktree add /tmp/okay-faces-pages gh-pages
   mkdir -p /tmp/okay-faces-pages/maven
   rsync -ac target/pages-maven/ /tmp/okay-faces-pages/maven/   # -c: a republished checksum keeps its size
   touch /tmp/okay-faces-pages/.nojekyll
   git -C /tmp/okay-faces-pages add -A
   git -C /tmp/okay-faces-pages commit -m "release 0.1.0"
   git -C /tmp/okay-faces-pages push origin gh-pages
   git push origin v0.1.0
   ```

   The first time, GitHub Pages is turned on for the branch `gh-pages` in the repository's settings.

5. The version back to the next `-SNAPSHOT`.

A release is never rewritten: a version published is what every build that resolved it has.
