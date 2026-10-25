package okay.faces.cloudflare

import okay.effects.Runner
import okay.effects.json.{Descent, Json}
import okay.http.{Request, Response}

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.scalajs.js
import scala.scalajs.js.Dynamic.{global => g}
import scala.scalajs.js.JSConverters._
import scala.scalajs.js.annotation.JSExportTopLevel

/**
 * THE APPLICATION ON CLOUDFLARE WORKERS (specs/cloudflare.md): the same program its faces serve on the JVM, each
 * SESSION A DURABLE OBJECT — the Worker routes `base/{id}` to the object of that id, makes a new id when a request
 * opens one; the rest (the look's stylesheet) answered in the Worker, keeping nothing. In the object, `Hosting`'s
 * program, its journal the object's storage: read when the object wakes, appended to as the session changes.
 *
 * The application's main for Cloudflare, one line — `Cloudflare.serve(Door.html(program), Door.rest(program))` —
 * linked as an ES module; the Worker's `entry.js` and `wrangler.toml` are `template/`'s. Nothing of this in the
 * program, nor in its other mains: a way of deploying it, beside the JVM's server and the page alone.
 */
object Cloudflare {
  private[cloudflare] var doors: Vector[Door] = Vector.empty
  /** `Hosting`'s answer run on the implementation given: the journal read before, what to append after */
  private[cloudflare] var answer: (Door, String, Held, Request, Boolean, List[Request]) => (List[Request], Response) =
    (_, _, _, req, _, _) => (Nil, Response.notFound(req.path))

  /** the doors served, on the implementation in scope: the first that a request opens or is of, takes it */
  def serve[F <: Runner with Singleton](doors: Door*)(implicit fx: F): Unit = {
    this.doors = doors.toVector
    val hosting = new Hosting[F]()(fx)
    type L[X] = okay.effects.Sum[hosting.fx.Pure, Kept, X]
    answer = (d, id, held, req, opens, journal) => hosting.fx.run(hosting.kept[L, hosting.fx.Pure, Response](journal)(
      hosting.answer[L](d, id, held, req, opens)(hosting.fx.splitLast[hosting.fx.Pure, Kept]))(
      hosting.fx.splitLast[hosting.fx.Pure, Kept]))
  }

  private[cloudflare] val doorHeader = "x-okay-faces-door"
  private[cloudflare] val idHeader = "x-okay-faces-session"
  private[cloudflare] val opensHeader = "x-okay-faces-opens"

  /** the Worker's `fetch`: a session's request to its object; one that opens a session, to a new one's */
  @JSExportTopLevel("okayFacesFetch")
  def fetch(request: js.Dynamic, env: js.Dynamic): js.Promise[js.Dynamic] =
    Wire.request(request).flatMap { req =>
      val opening = doors.indexWhere(_.opens(req))
      val of = doors.indices.view.flatMap(i => doors(i).idOf(req).map(i -> _)).headOption
      if (opening >= 0) toSession(env, req, opening, Wire.randomId(), opens = true)
      else of match {
        case Some((i, id)) => toSession(env, req, i, id, opens = false)
        case None => Future.successful(Wire.response(stateless(req)))
      }
    }.toJSPromise

  /** what no session holds: each door's own answer (a stylesheet), from a session never opened */
  private lazy val outside: Vector[Request => Response] = doors.map(_.session("-"))
  private def stateless(req: Request): Response =
    outside.iterator.map(_(req)).find(_.status != 404).getOrElse(Response.notFound(req.path))

  private def toSession(env: js.Dynamic, req: Request, door: Int, id: String, opens: Boolean): Future[js.Dynamic] = {
    val ns = env.OKAY_FACES_SESSIONS
    if (js.isUndefined(ns)) Future.successful(Wire.response(Response(500, Map("Content-Type" -> "text/plain"),
      "no Durable Object binding OKAY_FACES_SESSIONS: see okay-faces' web/cloudflare/template/wrangler.toml")))
    else {
      val stub = ns.get(ns.idFromName(s"${doors(door).base}/$id"))
      val headers = req.headers.map { case (k, vs) => k -> vs.mkString(", ") } ++ Map(doorHeader -> door.toString,
        idHeader -> id) ++ (if (opens) Map(opensHeader -> "1") else Map.empty)
      // a redirect is the browser's to follow (the HTML face's post-redirect-get), not the stub's
      stub.fetch(Wire.url(req), js.Dynamic.literal(method = req.method, headers = headers.toJSDictionary,
        redirect = "manual", body = if (req.method == "GET" || req.method == "HEAD") js.undefined else req.body: js.Any))
        .asInstanceOf[js.Promise[js.Dynamic]].toFuture
    }
  }
}

/**
 * A SESSION, A DURABLE OBJECT: `Hosting`'s answer, its journal the object's storage (`j:<n>`) — read when the object
 * is new, appended to after; Cloudflare holds the response until what is appended is stored.
 */
@JSExportTopLevel("OkayFacesSession")
class OkayFacesSession(state: js.Dynamic, env: js.Dynamic) extends js.Object {
  private val held = new Held
  private var journaled = 0

  def fetch(request: js.Dynamic): js.Promise[js.Dynamic] = Wire.request(request).flatMap { req =>
    val door = req.header(Cloudflare.doorHeader).flatMap(_.toIntOption).flatMap(Cloudflare.doors.lift)
    val id = req.header(Cloudflare.idHeader).getOrElse("")
    val opens = req.header(Cloudflare.opensHeader).contains("1")
    door match {
      case None => Future.successful(Wire.response(Response(400, Map.empty, "not a session's request")))
      case Some(d) => journal.flatMap { rs =>
          val (appended, response) = Cloudflare.answer(d, id, held, req, opens, rs)
          Future.traverse(appended) { r =>
            journaled += 1
            state.storage.put(f"j:$journaled%09d", Json.print(Hosting.json(r))).asInstanceOf[js.Promise[Unit]].toFuture
          }.map(_ => Wire.response(response))
        }
    }
  }.toJSPromise

  /** the journal, read only when the session is not held: the object new */
  private def journal: Future[List[Request]] =
    if (held.service.nonEmpty) Future.successful(Nil)
    else state.storage.list(js.Dynamic.literal(prefix = "j:")).asInstanceOf[js.Promise[js.Dynamic]].toFuture.map { m =>
      val entries = js.Array.from(m.values().asInstanceOf[js.Iterable[String]]).toList
      journaled = entries.size
      entries.flatMap(t => Descent.parse(t).toOption).flatMap(Hosting.fromJson)
    }
}

/** between Cloudflare's requests and responses and okay-http's */
private[cloudflare] object Wire {
  def request(r: js.Dynamic): Future[Request] = r.text().asInstanceOf[js.Promise[String]].toFuture.map { body =>
    val url = js.Dynamic.newInstance(g.URL)(r.url)
    val headers = scala.collection.mutable.Map.empty[String, List[String]]
    r.headers.forEach({ (v: String, k: String) => headers(k.toLowerCase) = headers.getOrElse(k.toLowerCase, Nil) :+ v })
    val query = scala.collection.mutable.LinkedHashMap.empty[String, List[String]]
    url.searchParams.forEach({ (v: String, k: String) => query(k) = query.getOrElse(k, Nil) :+ v })
    Request(r.method.asInstanceOf[String], url.pathname.asInstanceOf[String], query.toMap, headers.toMap, body)
  }

  def url(req: Request): String = {
    val q = req.query.toList.flatMap { case (k, vs) => vs.map(v => s"${enc(k)}=${enc(v)}") }
    "https://session" + req.path + (if (q.isEmpty) "" else q.mkString("?", "&", ""))
  }
  private def enc(s: String): String = js.URIUtils.encodeURIComponent(s)

  def response(r: Response): js.Dynamic = {
    val empty = r.status == 204 || r.status == 304 || (r.status >= 300 && r.status < 400 && r.body.isEmpty)
    js.Dynamic.newInstance(g.Response)(if (empty) null else r.body: js.Any,
      js.Dynamic.literal(status = r.status, headers = r.headers.toJSDictionary))
  }

  def randomId(): String = {
    val bytes = new js.typedarray.Uint8Array(16)
    g.crypto.getRandomValues(bytes)
    bytes.toArray.map(b => f"${b.toInt & 255}%02x").mkString
  }
}
