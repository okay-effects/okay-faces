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

  /** WHO THE PERSON IS, by the phone's own sign-in — `apple` (Sign in with Apple) or `google` (Google's account on the
   * phone, for the app's web client `client`): the identity token the issuer signed, for a backend to check; none
   * when the person did not, or the phone cannot */
  final case class Identity[X](provider: String, client: String, answer: Option[String] => X) extends Device[X]
  /** THE PHONE'S ADDRESS FOR NOTIFICATIONS: `apns:<token>` on an iPhone, `fcm:<token>` on Android — the person asked
   * to allow notifications first; none when they did not, or the app has no service (APNs' entitlement, Firebase's
   * configuration) */
  final case class PushToken[X](answer: Option[String] => X) extends Device[X]
  /** the person's language, as the phone is set: a code (`en`, `ru-RU`, `pl`) */
  final case class Language[X](answer: String => X) extends Device[X]

  /** A SOCKET (a WebSocket) opened to `url`: its id, or why not. What comes on it comes by itself, a device event
   * (`DeviceEvents`): `{"event": "socket", "id", "text"}`, and `{"event": "closed", "id", "why"}` when it ends */
  final case class Socket[X](url: String, answer: Either[String, String] => X) extends Device[X]
  /** a text sent on a socket; whether it went */
  final case class SocketSend[X](id: String, text: String, answer: Boolean => X) extends Device[X]
  /** a socket closed */
  final case class SocketClose[X](id: String, answer: Unit => X) extends Device[X]
  /** A TEXT SEALED (AES-256-GCM): the key and the result base64url, `aad` the associated data, a random IV — the
   * result `iv ‖ ciphertext ‖ tag` (okay-serverless' Sealer's frame) */
  final case class Seal[X](key: String, aad: String, plain: String, answer: String => X) extends Device[X]
  /** a sealed text opened by the key; none when it does not open (another key, tampered) */
  final case class Unseal[X](key: String, aad: String, box: String, answer: Option[String] => X)
    extends Device[X]
  /** A QR CODE read by the camera: its text, none when the person did not */
  final case class Scan[X](answer: Option[String] => X) extends Device[X]

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
  trait Port {
    def call(request: Json): Json
    /** what the device tells by itself (a socket's text, its closing): whoever listens is given it */
    def events: DeviceEvents = DeviceEvents.none
  }

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
    case Language(_) => Json.obj("op" -> Json.Str("language"))
    case PushToken(_) => Json.obj("op" -> Json.Str("push"))
    case Identity(p, c, _) => Json.obj("op" -> Json.Str("identity"), "provider" -> Json.Str(p), "client" -> Json.Str(c))
    case Socket(u, _) => Json.obj("op" -> Json.Str("socket"), "url" -> Json.Str(u))
    case SocketSend(i, t, _) => Json.obj("op" -> Json.Str("socketSend"), "id" -> Json.Str(i), "text" -> Json.Str(t))
    case SocketClose(i, _) => Json.obj("op" -> Json.Str("socketClose"), "id" -> Json.Str(i))
    case Seal(k, a, t, _) => Json.obj("op" -> Json.Str("seal"), "key" -> Json.Str(k), "aad" -> Json.Str(a),
        "text" -> Json.Str(t))
    case Unseal(k, a, t, _) => Json.obj("op" -> Json.Str("unseal"), "key" -> Json.Str(k), "aad" -> Json.Str(a),
        "text" -> Json.Str(t))
    case Scan(_) => Json.obj("op" -> Json.Str("scan"))
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
    case Language(k) => k(text(j, "value").getOrElse("en"))
    case PushToken(k) => k(text(j, "value"))
    case Identity(_, _, k) => k(text(j, "value"))
    case Socket(_, k) => k(text(j, "id").toRight(text(j, "error").getOrElse("the socket did not open")))
    case SocketSend(_, _, k) => k(flag(j, "ok"))
    case SocketClose(_, k) => k(())
    case Seal(_, _, _, k) => k(text(j, "value").getOrElse(""))
    case Unseal(_, _, _, k) => k(text(j, "value"))
    case Scan(k) => k(text(j, "value"))
  }

  /** A DEVICE IN MEMORY, for a test or a host with no phone: what is kept and hidden in maps, the network a
   * function, the time a clock, what was notified and opened kept to look at */
  final class Memory(network: (String, String, String) => Reply = (_, u, _) => Reply(404, Map.empty, u),
    clock: () => Long = () => System.currentTimeMillis(), val photo: Option[String] = None,
    val place: Option[Place] = None, val person: Boolean = true, val language: String = "en",
    val identity: Map[String, String] = Map.empty, val push: Option[String] = None,
    sockets: (String, String => Unit) => Option[String => Unit] = (_, _) => None,
    val scanned: Option[String] = None) extends Port {
    override val events: DeviceEvents = new DeviceEvents
    /** the sockets open: each its sender, and what to tell when it is closed */
    private val open = scala.collection.mutable.Map.empty[String, String => Unit]
    private var opened0 = 0
    val shared = scala.collection.mutable.ListBuffer.empty[String]
    /** the headers of the request the network is asked now — for a network that sends them on */
    @volatile var asking: List[(String, String)] = Nil
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
          asking = j match { case o: Json.Obj => o.get("headers").collect { case h: Json.Obj =>
            h.fields.toList.collect { case (k, Json.Str(v)) => k -> v } }.getOrElse(Nil); case _ => Nil }
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
        case "language" => Json.obj("value" -> Json.Str(language))
        case "push" => push.fold[Json](Json.Obj.empty)(t => Json.obj("value" -> Json.Str(t)))
        case "identity" => identity.get(text(j, "provider").getOrElse(""))
            .fold[Json](Json.Obj.empty)(t => Json.obj("value" -> Json.Str(t)))
        case "socket" =>
          opened0 += 1
          val id = s"s$opened0"
          sockets(text(j, "url").getOrElse(""), t => events.deliver(Json.obj("event" -> Json.Str("socket"),
            "id" -> Json.Str(id), "text" -> Json.Str(t)))) match {
            case None => Json.obj("error" -> Json.Str("the socket did not open"))
            case Some(send) => open(id) = send; Json.obj("id" -> Json.Str(id))
          }
        case "socketSend" => open.get(text(j, "id").getOrElse("")) match {
            case None => Json.obj("ok" -> Json.Bool(false))
            case Some(send) => send(text(j, "text").getOrElse("")); Json.obj("ok" -> Json.Bool(true))
          }
        case "socketClose" =>
          val id = text(j, "id").getOrElse("")
          if (open.remove(id).isDefined)
            events.deliver(Json.obj("event" -> Json.Str("closed"), "id" -> Json.Str(id), "why" -> Json.Str("closed")))
          Json.Obj.empty
        case "seal" => Json.obj("value" -> Json.Str(Sealing.seal(text(j, "key").getOrElse(""),
            text(j, "aad").getOrElse(""), text(j, "text").getOrElse(""))))
        case "unseal" => Sealing.unseal(text(j, "key").getOrElse(""), text(j, "aad").getOrElse(""),
            text(j, "text").getOrElse("")).fold[Json](Json.Obj.empty)(v => Json.obj("value" -> Json.Str(v)))
        case "scan" => scanned.fold[Json](Json.Obj.empty)(v => Json.obj("value" -> Json.Str(v)))
        case other => Json.obj("error" -> Json.Str(s"no operation $other"))
      }
    }
  }
  def memory(): Memory = new Memory()

  /** a port over text: what crosses C is a string each way; what the device tells by itself, to `told` */
  def textual(through: String => String, told: DeviceEvents = DeviceEvents.none): Port = new Port {
    def call(request: Json): Json =
      Descent.parse(through(Json.print(request))).getOrElse(Json.obj("error" -> Json.Str("not JSON")))
    override def events: DeviceEvents = told
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
  /** the identity token of the phone's sign-in with `provider` (`apple`, `google`) */
  def identityToken[R[_]](provider: String, client: String = "")(implicit in: In[Device, R]): Option[String] ! R =
    perform[Device].apply[R, Option[String]](Identity[Option[String]](provider, client, t => t))
  /** the phone's address for notifications: `apns:…`, `fcm:…` */
  def pushToken[R[_]](implicit in: In[Device, R]): Option[String] ! R =
    perform[Device].apply[R, Option[String]](PushToken[Option[String]](t => t))
  /** the person's language, as the phone is set */
  def language[R[_]](implicit in: In[Device, R]): String ! R =
    perform[Device].apply[R, String](Language[String](l => l))
  def verify[R[_]](reason: String)(implicit in: In[Device, R]): Boolean ! R =
    perform[Device].apply[R, Boolean](Verify[Boolean](reason, b => b))
}

object phone extends DeviceWords[Tagless.type]()(Tagless)

/** WHAT THE DEVICE TELLS BY ITSELF — a socket's text, its closing: each event given to everyone listening, in the
 * order it came. The shell tells it through `okay_faces_device_event`, on the library's thread */
final class DeviceEvents {
  private var listeners = Vector.empty[(Int, Json => Unit)]
  private var next = 0
  /** `f` given every event from now; the way to stop it */
  def listen(f: Json => Unit): () => Unit = synchronized {
    next += 1
    val id = next
    listeners :+= ((id, f))
    () => synchronized { listeners = listeners.filterNot(_._1 == id) }
  }
  def deliver(event: Json): Unit = { val ls = synchronized(listeners); ls.foreach(_._2(event)) }
}
object DeviceEvents {
  /** a device that tells nothing */
  val none: DeviceEvents = new DeviceEvents
}
