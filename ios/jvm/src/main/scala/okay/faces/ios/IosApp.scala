package okay.faces.ios

/** AN APPLICATION FOR THE SHELL, on the JVM: the same trait as Scala Native's, so an application and its tests are
 * built here too; found by its name, as the shell finds it (`IosApp.named`) */
trait IosApp {
  def dialogue: IosDialogue
}
object IosApp {
  def named(app: String): Either[String, IosApp] =
    try Right(Class.forName(app).getDeclaredConstructor().newInstance().asInstanceOf[IosApp])
    catch { case e: Exception => Left(s"no application $app: ${e.getMessage}") }
}
