package okay.faces.ios

import scala.scalanative.reflect.Reflect
import scala.scalanative.reflect.annotation.EnableReflectiveInstantiation
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._
import scala.scalanative.libc.stdlib

/**
 * AN APPLICATION FOR THE SHELL (specs/ios.md): a class with no parameters, found by its name — the one line of the
 * Swift side, `OkayFaces.open("my.App")` — and its dialogue. Reflectively instantiable, so the library needs no
 * registry and no `main`: a static library has none.
 */
@EnableReflectiveInstantiation
trait IosApp {
  def dialogue: IosDialogue
  /** the phone this app runs on: its operations to the shell's Swift (`okay_faces_on_device`) */
  def device: Device.Port = IosExports.device
}

/**
 * THE C FUNCTIONS the shell calls (`okay_faces.h`): one dialogue a process, every string returned the caller's, freed
 * by `okay_faces_free`. What goes wrong is a screen with `error`, never an exception through C.
 */
object IosExports {
  @volatile private var current: Option[IosDialogue] = None
  @volatile private var moved: Option[CFuncPtr0[Unit]] = None
  @volatile private var devices: Option[CFuncPtr1[CString, CString]] = None

  /** THE PHONE, through the shell: an operation as JSON to the function it registered, the answer back — a string
   * the shell made with `malloc` (`strdup`), freed here. No shell registered: every operation an error */
  val device: Device.Port = Device.textual { request =>
    devices match {
      case None => """{"error": "no device: the shell registers one with okay_faces_on_device"}"""
      case Some(f) => Zone.acquire { implicit z =>
          val answer = f(toCString(request))
          if (answer == null) """{"error": "no answer"}"""
          else { val s = fromCString(answer); stdlib.free(answer.asInstanceOf[Ptr[Byte]]); s }
        }
    }
  }

  /** a Scala string as C's: UTF-8, ended by a zero, in memory of `malloc` */
  def out(s: String): CString = {
    val bytes = s.getBytes("UTF-8")
    val p = stdlib.malloc((bytes.length + 1).toUSize).asInstanceOf[CString]
    var i = 0
    while (i < bytes.length) { p(i) = bytes(i); i += 1 }
    p(bytes.length) = 0.toByte
    p
  }
  private def error(message: String): String =
    okay.effects.json.Json.print(okay.effects.json.Json.obj("error" -> okay.effects.json.Json.Str(message)))

  /** the application, by its class name, made and opened */
  def open(app: String): String =
    Reflect.lookupInstantiatableClass(app) match {
      case None => error(s"no application $app: a class extending okay.faces.ios.IosApp")
      case Some(c) => IosFace.guarded {
          val d = c.newInstance().asInstanceOf[IosApp].dialogue
          d.onMoved(() => moved.foreach(f => f()))
          current = Some(d)
          d.open()
        }
    }
  def act(choice: String): String = current.fold(error("no application open"))(_.act(choice))
  def back(): String = current.fold(error("no application open"))(_.back())
  def look(): String = current.fold(error("no application open"))(_.look)
  def screen(): String = current.fold(error("no application open"))(_.open())

  @exported("okay_faces_open")
  def okayFacesOpen(app: CString): CString = out(open(fromCString(app)))
  @exported("okay_faces_act")
  def okayFacesAct(choice: CString): CString = out(act(fromCString(choice)))
  @exported("okay_faces_back")
  def okayFacesBack(): CString = out(back())
  @exported("okay_faces_screen")
  def okayFacesScreen(): CString = out(screen())
  @exported("okay_faces_look")
  def okayFacesLook(): CString = out(look())
  @exported("okay_faces_on_moved")
  def okayFacesOnMoved(f: CFuncPtr0[Unit]): Unit = moved = Option(f)
  @exported("okay_faces_on_device")
  def okayFacesOnDevice(f: CFuncPtr1[CString, CString]): Unit = devices = Option(f)
  @exported("okay_faces_free")
  def okayFacesFree(s: CString): Unit = stdlib.free(s.asInstanceOf[Ptr[Byte]])
}
