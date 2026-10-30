package okay.faces.mobile

/** AN APPLICATION FOR THE SHELL, on the JVM — and on Android, whose runtime runs JVM bytecode: the same trait as
 * Scala Native's, so an application and its tests are built here too; found by its name, as the shell finds it
 * (`MobileApp.named`) */
trait MobileApp {
  def dialogue: MobileDialogue
  /** the phone this app runs on: the one `MobileApp.phone` holds — a phone in memory until a shell sets its own */
  def device: Device.Port = MobileApp.phone
}
object MobileApp {
  @volatile var phone: Device.Port = Device.memory()
  def named(app: String): Either[String, MobileApp] =
    try Right(Class.forName(app).getDeclaredConstructor().newInstance().asInstanceOf[MobileApp])
    catch { case e: Exception => Left(s"no application $app: ${e.getMessage}") }
}

/**
 * THE SHELL'S WAY IN ON ANDROID (specs/mobile.md, "Android"): what Kotlin calls, in Java's terms — no Scala function,
 * no `Either`. The phone is set first (`device`), the application opened by its class name, its screens and choices
 * text each way, as on an iPhone through C.
 */
object Shell {
  /** the phone, as a shell answers it: an operation as JSON text in, its answer as JSON text out */
  trait Phone { def call(request: String): String }

  /** every app's device from now on: the shell's */
  def device(phone: Phone): Unit = MobileApp.phone = Device.textual(r => phone.call(r))

  /** the application by its class name, opened; a screen with `error` when there is none */
  def open(app: String): Opened = MobileApp.named(app) match {
    case Left(e) => new Opened(None, e)
    case Right(a) =>
      try new Opened(Some(a.dialogue), "")
      catch { case e: Throwable => new Opened(None, Option(e.getMessage).getOrElse(e.toString)) }
  }

  /** an application opened: its dialogue, or why there is none */
  final class Opened(d: Option[MobileDialogue], why: String) {
    private def error: String = okay.effects.json.Json.print(okay.effects.json.Json.obj(
      "error" -> okay.effects.json.Json.Str(why)))
    def screen(): String = d.fold(error)(_.open())
    def act(choice: String): String = d.fold(error)(_.act(choice))
    def back(): String = d.fold(error)(_.back())
    def look(): String = d.fold("{}")(_.look)
    /** told when an event moved the dialogue on by itself */
    def onMoved(moved: Runnable): Unit = d.foreach(_.onMoved(() => moved.run()))
  }
}
