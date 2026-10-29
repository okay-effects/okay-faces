package okay.faces.ios

import okay.effects.machine.Machine
import okay.faces._
import scala.scalanative.unsafe._

/** the hello the shell opens by name, in this test */
class HelloApp extends IosApp {
  import okay.faces._
  val p: String ! Face = offer[String, Face](View.heading("Hello"),
    List(Action.plain[String]("wave", "Wave")("waved"))).flatMap(w => show[Face](View.text(w)).map(_ => w))
  def dialogue: IosDialogue = IosFace[Machine.type, String](p)(Machine)
}

class IosExportsSuite extends munit.FunSuite {
  test("the C functions: an application by its name, a choice, the look; strings the caller's, freed") {
    Zone.acquire { implicit z =>
      val first = IosExports.okayFacesOpen(toCString("okay.faces.ios.HelloApp"))
      val screen = fromCString(first)
      IosExports.okayFacesFree(first)
      assert(screen.contains("\"Hello\""), screen)
      val next = fromCString(IosExports.okayFacesAct(toCString("""{"action": "wave"}""")))
      assert(next.contains("waved") && next.contains("\"done\":true"), next)
      assert(fromCString(IosExports.okayFacesLook()).contains("\"palette\""))
    }
  }
  test("an application not there: a screen with the error, not a crash") {
    Zone.acquire { implicit z =>
      assert(fromCString(IosExports.okayFacesOpen(toCString("no.Such"))).contains("\"error\""))
    }
  }
}
