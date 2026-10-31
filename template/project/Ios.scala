import sbt._
import scala.scalanative.sbtplugin.ScalaNativePlugin.autoImport._
import scala.scalanative.build._

/** THE APP'S LIBRARY FOR AN iPHONE: Scala Native linked into a static library for `OKAY_IOS_TARGET` — `simulator`
 * (the default) or `device` — against the SDK `xcrun` finds; `host` for the Mac's own. Linked for size. */
object Ios {
  def sdk(name: String): String = scala.sys.process.Process(Seq("xcrun", "--sdk", name, "--show-sdk-path")).!!.trim
  val library: Seq[Def.Setting[_]] = Seq(nativeConfig ~= { c =>
    val base = c.withBuildTarget(BuildTarget.libraryStatic).withGC(GC.immix).withMode(Mode.releaseSize)
    sys.env.getOrElse("OKAY_IOS_TARGET", "simulator") match {
      case "host" => base
      case target =>
        val (triple, sdkName) =
          if (target == "device") ("arm64-apple-ios17.0", "iphoneos") else ("arm64-apple-ios17.0-simulator", "iphonesimulator")
        val root = sdk(sdkName)
        base.withTargetTriple(triple).withCompileOptions(c.compileOptions ++ Seq("-isysroot", root))
          .withLinkingOptions(c.linkingOptions ++ Seq("-isysroot", root))
    }
  })
}
