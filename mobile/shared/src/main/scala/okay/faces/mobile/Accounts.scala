package okay.faces.mobile

import okay.effects.json.{Descent, Json}
import okay.effects.shape.Shape
import okay.effects.tagless.Tagless
import okay.faces.{Action, Face, View}

/**
 * WHO THE PERSON IS, AND WHAT OF THEIRS THE CLOUD KEEPS (okay-serverless' specs/accounts.md, the phone's side): a
 * sign-in screen as a component — Apple, Google, a code by mail — into a session kept in the keychain; and the values
 * the program marks as SHARED, kept on the phone and in the cloud alike, so the person's other phones have them.
 * Everything else the program keeps stays on the phone. Words over `Device` and `Face`, no effect of their own: the
 * backend is `Backend(url)`, an okay-serverless `accounts` app.
 */
final case class Backend(url: String, appleClient: String = "", googleClient: String = "")

object Accounts {
  import okay.faces._
  import okay.faces.mobile.phone._
  type P[X] = Device.Phone[X]
  private val sessionKey = "accounts.session"
  private val whoKey = "accounts.who"

  private def field(j: Json, k: String): Option[String] =
    j match { case o: Json.Obj => o.get(k).collect { case Json.Str(s) => s; case Json.Num(n) => n.toString }
      case _ => None }
  private def post(b: Backend, path: String, body: Json, session: Option[String] = None): Device.Reply ! P =
    fetch[P](b.url.stripSuffix("/") + path, "POST", Json.print(body),
      List("content-type" -> "application/json") ++ session.map(s => "authorization" -> s"Bearer $s"))

  /** who is signed in, if anyone: `email:ann@example.org`, `apple:…`, `google:…` */
  def who: Option[String] ! P = secret[P](whoKey)
  private def session: Option[String] ! P = secret[P](sessionKey)

  /** a session taken from the backend's answer, kept; whether there was one */
  private def keep(r: Device.Reply): Boolean ! P =
    Descent.parse(r.body).toOption.filter(_ => r.ok) match {
      case Some(j) if field(j, "session").isDefined =>
        hide[P](sessionKey, field(j, "session")).flatMap(_ => hide[P](whoKey, field(j, "who"))).map(_ => true)
      case _ => pure[Boolean, P](false)
    }

  sealed trait Way
  case object Apple extends Way
  case object Google extends Way
  final case class Mail(address: String) extends Way
  case object Later extends Way

  private val address: Shape[String] = Shape.text.described("Email", Some("a code comes to it"))
  private val digits: Shape[String] = Shape.text.described("Code", Some("the six digits from the mail"))

  /** THE SIGN-IN SCREEN: Apple (on an iPhone), Google (on Android), a code by mail; whether the person signed in */
  def signIn(b: Backend): Boolean ! P =
    offer[Way, P](View.stack(View.heading("Sign in"), View.text("To have your shared things on all your phones.")),
      (if (b.appleClient.nonEmpty) List(Action.plain[Way]("apple", "Sign in with Apple")(Apple)) else Nil) ++
        (if (b.googleClient.nonEmpty) List(Action.plain[Way]("google", "Sign in with Google")(Google)) else Nil) ++
        List(Action[String, Way]("email", "Email me a code",
          Shape.record(Shape.field("email", address))(a => a)(a => a))(Mail(_)),
          Action.plain[Way]("later", "Not now")(Later))).flatMap {
      case Later => pure[Boolean, P](false)
      case Apple => token(b, "apple", b.appleClient)
      case Google => token(b, "google", b.googleClient)
      case Mail(a) => post(b, "/auth/email", Json.obj("email" -> Json.Str(a))).flatMap { r =>
          if (!r.ok) show[P](View.problem(s"No code sent: ${Descent.parse(r.body).toOption.flatMap(field(_, "error"))
            .getOrElse(r.body)}")).flatMap(_ => signIn(b))
          else code(b, a, Descent.parse(r.body).toOption.flatMap(field(_, "code")))
        }
    }

  /** the code from the mail typed; a test backend tells it on the screen (`ACCOUNTS_TEST`) */
  private def code(b: Backend, a: String, told: Option[String]): Boolean ! P =
    offer[String, P](View.Group(None, Vector(View.heading("The code"), View.text(s"Sent to $a.")) ++
        told.map(c => View.info(s"A test server's code: $c"))),
      List(Action[String, String]("code", "Sign in", Shape.record(Shape.field("code", digits))(c => c)(c => c))(c => c),
        Action.plain[String]("back", "Another way")(""))).flatMap {
      case "" => signIn(b)
      case c => post(b, "/auth/email/code", Json.obj("email" -> Json.Str(a), "code" -> Json.Str(c.trim))).flatMap(
          keep).flatMap(ok => if (ok) pure[Boolean, P](true)
          else show[P](View.problem("Not the code.")).flatMap(_ => code(b, a, told)))
    }

  private def token(b: Backend, provider: String, client: String): Boolean ! P =
    identityToken[P](provider, client).flatMap {
      case None => show[P](View.warning("Not signed in.")).flatMap(_ => signIn(b))
      case Some(t) => post(b, s"/auth/$provider", Json.obj("token" -> Json.Str(t))).flatMap(keep).flatMap(ok =>
          if (ok) pure[Boolean, P](true) else show[P](View.problem("The sign-in was not accepted."))
            .flatMap(_ => signIn(b)))
    }

  def signOut(b: Backend): Unit ! P =
    session.flatMap(s => s.fold(pure[Device.Reply, P](Device.Reply(200, Map.empty, "")))(t =>
      post(b, "/auth/out", Json.Obj.empty, Some(t)))).flatMap(_ =>
      hide[P](sessionKey, None).flatMap(_ => hide[P](whoKey, None)))

  private def url(b: Backend, key: String): String =
    b.url.stripSuffix("/") + "/shared/" + java.net.URLEncoder.encode(key, "UTF-8")

  /** A SHARED VALUE: the cloud's when signed in and it answers (kept on the phone too), else the phone's own copy */
  def getShared(b: Backend, key: String): Option[String] ! P =
    session.flatMap {
      case None => load[P](s"shared:$key")
      case Some(s) => fetch[P](url(b, key), headers = List("authorization" -> s"Bearer $s")).flatMap { r =>
          if (r.ok) Descent.parse(r.body).toOption.flatMap(field(_, "value")) match {
            case Some(v) => save[P](s"shared:$key", v).map(_ => Option(v))
            case None => load[P](s"shared:$key")
          }
          else if (r.status == 404) forget[P](s"shared:$key").map(_ => Option.empty[String])
          else load[P](s"shared:$key")
        }
    }

  /** a shared value set: on the phone, and in the cloud when signed in (the last write wins); whether the cloud has
   * it */
  def putShared(b: Backend, key: String, value: String): Boolean ! P =
    save[P](s"shared:$key", value).flatMap(_ => session).flatMap {
      case None => pure[Boolean, P](false)
      case Some(s) => fetch[P](url(b, key), "PUT", Json.print(Json.obj("value" -> Json.Str(value))),
          List("content-type" -> "application/json", "authorization" -> s"Bearer $s")).map(_.ok)
    }
}
