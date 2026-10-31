package okay.faces.mobile

import okay.effects.{Effects, Sum, Void}
import okay.effects.json.{Descent, Json}
import okay.effects.tagless.Tagless
import okay.faces.Face

/**
 * THE PHONE, AS EFFECTS (specs/ios.md, "The device"): what a program asks of the device it runs on — the network, a
 * place to keep things, the keychain, the time, a notification, a link opened — as operations, data with their
 * answers. The program says what; the handler is the platform's: on an iPhone the shell's Swift (URLSession,
 * UserDefaults, the keychain, UserNotifications), in a test a `Device.memory`. Every operation is answered in place:
 * the program waits for it, on the thread the shell calls the library on — never the screen's.
 */
sealed trait Device[X]
object Device {
  /** a request to the network: its method, address, headers, body; the reply — a failure to reach it is status 0,
   * the reason the body */
  final case class Fetch[X](method: String, url: String, headers: List[(String, String)], body: String,
    answer: Reply => X) extends Device[X]
  /** what was kept under a key, if anything (UserDefaults on an iPhone) */
  final case class Load[X](key: String, answer: Option[String] => X) extends Device[X]
  /** a value kept under a key; `None` forgets it */
  final case class Save[X](key: String, value: Option[String], answer: Unit => X) extends Device[X]
  /** a secret kept under a key, if any (the keychain) */
  final case class Secret[X](key: String, answer: Option[String] => X) extends Device[X]
  /** a secret kept, or forgotten (`None`) */
  final case class Hide[X](key: String, value: Option[String], answer: Unit => X) extends Device[X]
  /** the time, in milliseconds since 1970 */
  final case class Now[X](answer: Long => X) extends Device[X]
  /** a notification to the person, `after` seconds from now (0: at once); whether they allow notifications */
  final case class Notify[X](title: String, body: String, after: Long, answer: Boolean => X) extends Device[X]
  /** a link opened by the phone (the browser, the mail, a call); whether it could */
  final case class Open[X](url: String, answer: Boolean => X) extends Device[X]
  /** A PHOTO the person takes (`camera`) or picks (`library`): a JPEG at most `size` pixels across, as a data: URI —
   * what `View.Image` shows and a request sends; none when they did not */
  final case class Photo[X](from: String, size: Int, answer: Option[String] => X) extends Device[X]
  /** where the phone is, if the person lets the app know */
  final case class Locate[X](answer: Option[Place] => X) extends Device[X]
  /** a text (and a link) handed to the phone's sharing — a message, the mail, another app; whether it went */
  final case class Share[X](text: String, url: Option[String], answer: Boolean => X) extends Device[X]
  /** the person themselves, by the phone's own check — Face ID, a fingerprint, the passcode; `reason` said to them */
  final case class Verify[X](reason: String, answer: Boolean => X) extends Device[X]

  /** a place: degrees, and how far off it may be, in metres */
  final case class Place(latitude: Double, longitude: Double, accuracy: Double)

  /** the network's answer */
  final case class Reply(status: Int, headers: Map[String, String], body: String) {
    def ok: Boolean = status >= 200 && status < 300
  }

  /** THE ROW OF AN APP ON A PHONE: its device, then its screens — `Row[*[Pure] + *[Device] + *[Face], X]` in Scala 3 */
  type Rest[X] = Sum[Void, Device, X]
  type Phone[X] = Sum[Rest, Face, X]

  /**
   * THE PLATFORM'S SIDE, one call: an operation as JSON in, its answer as JSON out (specs/ios.md has the shapes). The
   * shell's Swift is one (`MobileExports`, through a C function it registers), `memory` another.
   */
  trait Port { def call(request: Json): Json }

  /** an operation as the port reads it */
  def request(op: Device[_]): Json = op match {
    case Fetch(m, u, hs, b, _) => Json.obj("op" -> Json.Str("fetch"), "method" -> Json.Str(m), "url" -> Json.Str(u),
        "headers" -> Json.Obj(hs.toVector.map { case (k, v) => k -> Json.Str(v) }), "body" -> Json.Str(b))
    case Load(k, _) => Json.obj("op" -> Json.Str("load"), "key" -> Json.Str(k))
    case Save(k, v, _) => Json.Obj(Vector("op" -> Json.Str("save"), "key" -> Json.Str(k)) ++
        v.map(x => "value" -> Json.Str(x)))
    case Secret(k, _) => Json.obj("op" -> Json.Str("secret"), "key" -> Json.Str(k))
    case Hide(k, v, _) => Json.Obj(Vector("op" -> Json.Str("hide"), "key" -> Json.Str(k)) ++
        v.map(x => "value" -> Json.Str(x)))
    case Now(_) => Json.obj("op" -> Json.Str("now"))
    case Notify(t, b, a, _) => Json.obj("op" -> Json.Str("notify"), "title" -> Json.Str(t), "body" -> Json.Str(b),
        "after" -> Json.num(a))
    case Open(u, _) => Json.obj("op" -> Json.Str("open"), "url" -> Json.Str(u))
    case Photo(f, s, _) => Json.obj("op" -> Json.Str("photo"), "from" -> Json.Str(f), "size" -> Json.num(s))
    case Locate(_) => Json.obj("op" -> Json.Str("locate"))
    case Share(t, u, _) => Json.Obj(Vector("op" -> Json.Str("share"), "text" -> Json.Str(t)) ++
        u.map(x => "url" -> Json.Str(x)))
    case Verify(r, _) => Json.obj("op" -> Json.Str("verify"), "reason" -> Json.Str(r))
  }
  private def number(j: Json, k: String): Option[Double] = field(j, k).collect { case Json.Num(n) => n.toDouble }

  private def field(j: Json, k: String): Option[Json] = j match { case o: Json.Obj => o.get(k); case _ => None }
  private def text(j: Json, k: String): Option[String] = field(j, k).collect { case Json.Str(s) => s }
  private def flag(j: Json, k: String): Boolean = field(j, k).contains(Json.Bool(true))

  /** the answer the port gave, as the operation's: `{"value": ..}`, `{"ok": ..}`, a reply's fields */
  def answer[X](op: Device[X], j: Json): X = op match {
    case Fetch(_, _, _, _, k) =>
      val status = field(j, "status").collect { case Json.Num(n) => n.toInt }.getOrElse(0)
      val headers = field(j, "headers").collect { case o: Json.Obj => o.fields.collect { case (h, Json.Str(v)) =>
          h.toLowerCase -> v } }.getOrElse(Vector.empty).toMap
      k(Reply(status, headers, text(j, "body").orElse(text(j, "error")).getOrElse("")))
    case Load(_, k) => k(text(j, "value"))
    case Save(_, _, k) => k(())
    case Secret(_, k) => k(text(j, "value"))
    case Hide(_, _, k) => k(())
    case Now(k) => k(field(j, "value").collect { case Json.Num(n) => n.toLong }.getOrElse(0L))
    case Notify(_, _, _, k) => k(flag(j, "ok"))
    case Open(_, k) => k(flag(j, "ok"))
    case Photo(_, _, k) => k(text(j, "value"))
    case Locate(k) => k(for (la <- number(j, "latitude"); lo <- number(j, "longitude"))
        yield Place(la, lo, number(j, "accuracy").getOrElse(0.0)))
    case Share(_, _, k) => k(flag(j, "ok"))
    case Verify(_, k) => k(flag(j, "ok"))
  }

  /** A DEVICE IN MEMORY, for a test or a host with no phone: what is kept and hidden in maps, the network a
   * function, the time a clock, what was notified and opened kept to look at */
  final class Memory(network: (String, String, String) => Reply = (_, u, _) => Reply(404, Map.empty, u),
    clock: () => Long = () => System.currentTimeMillis(), val photo: Option[String] = None,
    val place: Option[Place] = None, val person: Boolean = true) extends Port {
    val shared = scala.collection.mutable.ListBuffer.empty[String]
    val kept = scala.collection.mutable.Map.empty[String, String]
    val hidden = scala.collection.mutable.Map.empty[String, String]
    val notified = scala.collection.mutable.ListBuffer.empty[(String, String)]
    val opened = scala.collection.mutable.ListBuffer.empty[String]
    private def put(m: scala.collection.mutable.Map[String, String], j: Json): Json = {
      val k = text(j, "key").getOrElse("")
      text(j, "value").fold(m.remove(k))(v => m.put(k, v)); Json.Obj.empty
    }
    private def got(m: scala.collection.mutable.Map[String, String], j: Json): Json =
      m.get(text(j, "key").getOrElse("")).fold[Json](Json.Obj.empty)(v => Json.obj("value" -> Json.Str(v)))
    def call(j: Json): Json = synchronized {
      text(j, "op").getOrElse("") match {
        case "fetch" =>
          val r = network(text(j, "method").getOrElse("GET"), text(j, "url").getOrElse(""),
            text(j, "body").getOrElse(""))
          Json.obj("status" -> Json.num(r.status), "headers" -> Json.Obj(r.headers.toVector.map { case (k, v) =>
              k -> Json.Str(v) }), "body" -> Json.Str(r.body))
        case "load" => got(kept, j)
        case "save" => put(kept, j)
        case "secret" => got(hidden, j)
        case "hide" => put(hidden, j)
        case "now" => Json.obj("value" -> Json.num(clock()))
        case "notify" => notified += ((text(j, "title").getOrElse(""), text(j, "body").getOrElse(""))); Json.obj(
            "ok" -> Json.Bool(true))
        case "open" => opened += text(j, "url").getOrElse(""); Json.obj("ok" -> Json.Bool(true))
        case "photo" => photo.fold[Json](Json.Obj.empty)(p => Json.obj("value" -> Json.Str(p)))
        case "locate" => place.fold[Json](Json.Obj.empty)(p => Json.obj("latitude" -> Json.Num(BigDecimal(p.latitude)),
            "longitude" -> Json.Num(BigDecimal(p.longitude)), "accuracy" -> Json.Num(BigDecimal(p.accuracy))))
        case "share" => shared += text(j, "text").getOrElse(""); Json.obj("ok" -> Json.Bool(true))
        case "verify" => Json.obj("ok" -> Json.Bool(person))
        case other => Json.obj("error" -> Json.Str(s"no operation $other"))
      }
    }
  }
  def memory(): Memory = new Memory()

  /** a port over text: what crosses C is a string each way */
  def textual(through: String => String): Port = new Port {
    def call(request: Json): Json =
      Descent.parse(through(Json.print(request))).getOrElse(Json.obj("error" -> Json.Str("not JSON")))
  }
}

/** THE DEVICE'S WORDS, over any implementation; `okay.faces.mobile.phone` over tagless, so a program is a value:
 * `import okay.faces._, okay.faces.mobile.phone._` */
class DeviceWords[F <: Effects with Singleton](implicit val fx: F) {
  import fx._
  import Device._
  def fetch[R[_]](url: String, method: String = "GET", body: String = "", headers: List[(String, String)] = Nil)(
    implicit in: In[Device, R]): Reply ! R = perform[Device].apply[R, Reply](Fetch[Reply](method, url, headers, body,
    r => r))
  def load[R[_]](key: String)(implicit in: In[Device, R]): Option[String] ! R =
    perform[Device].apply[R, Option[String]](Load[Option[String]](key, v => v))
  def save[R[_]](key: String, value: String)(implicit in: In[Device, R]): Unit ! R =
    perform[Device].apply[R, Unit](Save[Unit](key, Some(value), u => u))
  def forget[R[_]](key: String)(implicit in: In[Device, R]): Unit ! R =
    perform[Device].apply[R, Unit](Save[Unit](key, None, u => u))
  def secret[R[_]](key: String)(implicit in: In[Device, R]): Option[String] ! R =
    perform[Device].apply[R, Option[String]](Secret[Option[String]](key, v => v))
  def hide[R[_]](key: String, value: Option[String])(implicit in: In[Device, R]): Unit ! R =
    perform[Device].apply[R, Unit](Hide[Unit](key, value, u => u))
  def now[R[_]](implicit in: In[Device, R]): Long ! R = perform[Device].apply[R, Long](Now[Long](t => t))
  def remind[R[_]](title: String, body: String, after: Long = 0)(implicit in: In[Device, R]): Boolean ! R =
    perform[Device].apply[R, Boolean](Notify[Boolean](title, body, after, b => b))
  def open[R[_]](url: String)(implicit in: In[Device, R]): Boolean ! R =
    perform[Device].apply[R, Boolean](Open[Boolean](url, b => b))
  /** a photo the person takes with the camera, as a data: URI; at most `size` pixels across */
  def takePhoto[R[_]](size: Int = 1024)(implicit in: In[Device, R]): Option[String] ! R =
    perform[Device].apply[R, Option[String]](Photo[Option[String]]("camera", size, p => p))
  /** a photo the person picks from theirs */
  def pickPhoto[R[_]](size: Int = 1024)(implicit in: In[Device, R]): Option[String] ! R =
    perform[Device].apply[R, Option[String]](Photo[Option[String]]("library", size, p => p))
  def locate[R[_]](implicit in: In[Device, R]): Option[Place] ! R =
    perform[Device].apply[R, Option[Place]](Locate[Option[Place]](p => p))
  def share[R[_]](text: String, url: Option[String] = None)(implicit in: In[Device, R]): Boolean ! R =
    perform[Device].apply[R, Boolean](Share[Boolean](text, url, b => b))
  def verify[R[_]](reason: String)(implicit in: In[Device, R]): Boolean ! R =
    perform[Device].apply[R, Boolean](Verify[Boolean](reason, b => b))
}

object phone extends DeviceWords[Tagless.type]()(Tagless)
