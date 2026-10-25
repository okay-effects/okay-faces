package okay.faces.cloudflare

import okay.effects.{Effects, Runner}
import okay.effects.json.Json
import okay.effects.tagless.Program
import okay.faces.{Face, Step}
import okay.faces.html.HtmlFace
import okay.faces.rest.RestFace
import okay.http.{Https, Request, Response}

/** a session's service in a host: a request answered; the last point its program passed since asked (taken) */
trait Served {
  def apply(req: Request): Response
  def marked(): Option[Step.Point]
}

/**
 * A DOOR OF THE APPLICATION: where its sessions are, which request opens one, which session a request is of (its
 * path's segment after `base`, or a header — MCP's), the service of one session by its id — a face's handler, run,
 * told how to say the session moved on by itself (`moved`: a host that can push tells its pages), started AT a point
 * its program passed (`at`, a `Durable`'s), or from the start. `Door.html`, `Door.rest`, `Door.mcp`; `Door.shell`,
 * the thin page, opens none.
 */
final case class Door(base: String, opens: Request => Boolean, idOf: Request => Option[String],
  session: (String, () => Unit, Option[Step.Point]) => Served)

object Door {
  /** the session of a path `base/{id}/…` */
  def byPath(base: String): Request => Option[String] = req =>
    if (!req.path.startsWith(base.stripSuffix("/") + "/")) None
    else req.path.substring(base.stripSuffix("/").length + 1).split('/').headOption.filter(_.nonEmpty)

  /** a session's service of a face's handler, run on its implementation; the points it passes, kept for the asking */
  private def served(serve: Request => Response, last: () => Option[Step.Point]): Served = new Served {
    def apply(req: Request): Response = serve(req)
    def marked(): Option[Step.Point] = last()
  }
  private final class Last {
    private var point: Option[Step.Point] = None
    def put(p: Step.Point): Unit = point = Some(p)
    def take(): Option[Step.Point] = { val p = point; point = None; p }
  }

  /** the program as pages from the server (the HTML face): `base` opens a session, and `/` too unless `atRoot` is
   * false (the thin page there). The program made again for each session (by name): a handler it holds (a ledger in
   * memory) is the session's own. `from`: the program started at a point it passed (`Durable.from`) */
  def html[F <: Runner with Singleton, A](p: => Program[A, Face], settings: HtmlFace.Settings = HtmlFace.Settings(),
    atRoot: Boolean = true, from: Step.Point => Option[Program[A, Face]] = (_: Step.Point) => None)(
    implicit fx: F): Door = {
    val face = new HtmlFace[F]()(fx)
    val https = new Https[face.fx.type]()(face.fx)
    Door(settings.base, r => r.method == "GET" && ((atRoot && r.path == "/") || r.path == settings.base),
      byPath(settings.base), { (id, _, at) =>
        val last = new Last
        val service = face.handle[A](at.flatMap(from).getOrElse(p),
          settings.copy(ids = () => id, marked = (_, point) => last.put(point)))
        served(req => face.fx.run(https.scripted(Nil).apply[face.H, face.fx.Pure, Response](service(req))(
          face.fx.splitLast[face.fx.Pure, okay.http.Http]))._2, () => last.take())
      })
  }

  /** the program as a JSON API with links (the REST face; the thin page's): `POST base` opens a session; the program
   * made again for each session, as `html`'s, or at a point (`from`); a session that moves on by itself says so */
  def rest[F <: Runner with Singleton, A](p: => Program[A, Face], settings: RestFace.Settings = RestFace.Settings(),
    from: Step.Point => Option[Program[A, Face]] = (_: Step.Point) => None)(implicit fx: F): Door = {
    val face = new RestFace[F]()(fx)
    val https = new Https[face.fx.type]()(face.fx)
    Door(settings.base, r => r.method == "POST" && r.path == settings.base, byPath(settings.base), { (id, moved, at) =>
      val last = new Last
      val service = face.handle[A](at.flatMap(from).getOrElse(p), settings.copy(ids = () => id,
        moved = _ => moved(), marked = (_, point) => last.put(point)))
      served(req => face.fx.run(https.scripted(Nil).apply[face.H, face.fx.Pure, Response](service(req))(
        face.fx.splitLast[face.fx.Pure, okay.http.Http]))._2, () => last.take())
    })
  }

  /** the program served to agents (the MCP face, streamable HTTP at `path`): an `initialize` with no session opens
   * one, the session of a request its `Mcp-Session-Id`; the program made again for each session, or at a point */
  def mcp[F <: Runner with Singleton, A](p: => Program[A, Face],
    settings: okay.faces.mcp.McpFace.Settings = okay.faces.mcp.McpFace.Settings(), path: String = "/mcp",
    from: Step.Point => Option[Program[A, Face]] = (_: Step.Point) => None)(implicit fx: F): Door = {
    val face = new okay.faces.mcp.McpFace[F]()(fx)
    val https = new Https[face.fx.type]()(face.fx)
    Door(path,
      r => r.method == "POST" && r.path == path && r.header("mcp-session-id").isEmpty && r.body.contains("initialize"),
      r => if (r.path == path) r.header("mcp-session-id") else None, { (id, _, at) =>
        val last = new Last
        val service = face.served[A](at.flatMap(from).getOrElse(p), settings, path, () => id, point => last.put(point))
        served(req => face.fx.run(https.scripted(Nil).apply[face.H, face.fx.Pure, Response](service(req))(
          face.fx.splitLast[face.fx.Pure, okay.http.Http]))._2, () => last.take())
      })
  }

  /** THE THIN PAGE (the remote face's script, `RemotePage`), knowing no program: the page at `/`, its script at
   * `/index.js`, the look — no session; the program's sessions behind `Door.rest` */
  def shell[F <: Runner with Singleton](title: String, script: () => Either[String, String],
    look: Option[okay.faces.Look] = None)(implicit fx: F): Door = {
    val shell = new okay.faces.rest.Shell[F]()(fx)
    val https = new Https[shell.fx.type]()(shell.fx)
    val service = shell.handle(title, look, "/" -> script)
    Door("/", _ => false, _ => None, { (_, _, _) =>
      served(req => shell.fx.run(https.scripted(Nil).apply[shell.H, shell.fx.Pure, Response](service(req))(
        shell.fx.splitLast[shell.fx.Pure, okay.http.Http]))._2, () => None)
    })
  }
}

/** WHAT A HOST KEEPS OF A SESSION: the request that opened it, the last point its program passed, the requests that
 * changed it since */
final case class Saved(opening: Option[Request] = None, at: Option[Step.Point] = None, tail: List[Request] = Nil)

/** a write to a host's keeping */
sealed trait Write
object Write {
  final case class Opened(request: Request) extends Write
  /** a point passed: kept, and the requests before it forgotten */
  final case class Pointed(point: Step.Point) extends Write
  final case class Appended(request: Request) extends Write
}

/** A SESSION'S KEEPING, the host's effect: what is kept, read; a write */
sealed trait Kept[X]
object Kept {
  final case class Read[X](answer: Saved => X) extends Kept[X]
  final case class Put[X](write: Write, answer: Unit => X) extends Kept[X]
}

/** a session in a host's memory: its service, while the host is awake; how to tell its pages it moved */
final class Held(val moved: () => Unit = () => ()) {
  var service: Option[Served] = None
}

/**
 * A SESSION HOSTED WHERE MEMORY IS LOST (specs/cloudflare.md): a request answered by the session's service — as it is,
 * or, the host new, a new one STARTED AT THE LAST POINT its program passed (its state, data), the request that opened
 * it and those since that point played through it; then answered. What it keeps: the opening request; the point each
 * time the program passes one (the requests before it forgotten); a request that changed it after the point — one but
 * a read, unless refused. A program with no points is kept by all its requests, as before. Platform-neutral: a Durable
 * Object handles `Kept` by its storage, a test by a value.
 */
class Hosting[F <: Effects with Singleton](implicit implementation: F) {
  val fx: F = implementation
  import fx._

  def read[R[_]](implicit in: In[Kept, R]): Saved ! R = perform[Kept].apply[R, Saved](Kept.Read(s => s))
  def put[R[_]](w: Write)(implicit in: In[Kept, R]): Unit ! R = perform[Kept].apply[R, Unit](Kept.Put(w, u => u))

  def answer[R[_]](door: Door, id: String, held: Held, req: Request, opens: Boolean)(
    implicit in: In[Kept, R]): Response ! R = {
    val woken: Served ! R = held.service match {
      case Some(s) => pure[Served, R](s)
      case None => read[R].map { saved =>
          val s = door.session(id, held.moved, saved.at)
          saved.opening.foreach(r => s(r))
          saved.tail.foreach(r => s(r))
          s.marked()
          held.service = Some(s)
          s
        }
    }
    woken.flatMap { s =>
      val response = s(req)
      val point = s.marked()
      val kept = Hosting.kept(req)
      val writes: List[Write] =
        if (response.status >= 400) Nil
        else if (opens) Write.Opened(kept) :: point.map(Write.Pointed(_)).toList
        else point match {
          case Some(p) => List(Write.Pointed(p))
          case None => if (req.method != "GET") List(Write.Appended(kept)) else Nil
        }
      writes.foldLeft(pure[Unit, R](()))((acc, w) => acc.flatMap(_ => put[R](w))).map(_ => response)
    }
  }

  /** THE KEEPING GIVEN: read as `saved`, the writes collected in order — a host's storage read before, written after */
  def kept[R[_], Rest[_], A](saved: Saved)(p: A ! R)(implicit sp: Split[Kept, R, Rest]): (List[Write], A) ! Rest =
    handle[Kept, List[Write], R, Rest, A, (List[Write], A)](p, Nil,
      new StatefulTailHandler[Kept, List[Write], Rest, A, (List[Write], A)] {
        def ret(writes: List[Write], a: A) = pure((writes.reverse, a))
        def answer[X](cell: Cell[List[Write]], op: Kept[X]): X = op match {
          case Kept.Read(answer) => answer(saved)
          case Kept.Put(w, answer) => cell.set(w :: cell.get); answer(())
        }
      })

  /** what is kept after the writes: a host that keeps a value (a test) */
  def after(saved: Saved, writes: List[Write]): Saved = writes.foldLeft(saved) {
    case (s, Write.Opened(r)) => s.copy(opening = Some(r))
    case (s, Write.Pointed(p)) => s.copy(at = Some(p), tail = Nil)
    case (s, Write.Appended(r)) => s.copy(tail = s.tail :+ r)
  }
}

object Hosting {
  /** what a face reads of a request's headers, kept with it: its body's type, what it accepts, MCP's session */
  val headers: Set[String] = Set("content-type", "accept", "mcp-session-id", "mcp-protocol-version")
  /** a request as kept: its method, path, query, body and those headers */
  def kept(r: Request): Request = r.copy(headers = r.headers.filter { case (k, _) => headers(k.toLowerCase) })

  def json(r: Request): Json = Json.obj("method" -> Json.Str(r.method), "path" -> Json.Str(r.path),
    "query" -> Json.Obj(r.query.toVector.map { case (k, vs) => k -> Json.Arr(vs.toVector.map(Json.Str(_))) }),
    "headers" -> Json.Obj(r.headers.toVector.map { case (k, vs) => k -> Json.Arr(vs.toVector.map(Json.Str(_))) }),
    "body" -> Json.Str(r.body))

  private def lists(j: Option[Json]): Map[String, List[String]] = j.collect { case o: Json.Obj =>
    o.fields.toMap.map { case (k, v) =>
      k -> (v match { case Json.Arr(xs) => xs.toList.collect { case Json.Str(x) => x }; case _ => Nil }) }
  }.getOrElse(Map.empty)

  def pointJson(p: Step.Point): Json = Json.obj("name" -> Json.Str(p.name), "state" -> p.state)
  def pointFromJson(j: Json): Option[Step.Point] = j match {
    case o: Json.Obj =>
      val fs = o.fields.toMap
      fs.get("name").collect { case Json.Str(n) => Step.Point(n, fs.getOrElse("state", Json.Null)) }
    case _ => None
  }

  def fromJson(j: Json): Option[Request] = j match {
    case o: Json.Obj =>
      val fs = o.fields.toMap
      def s(k: String) = fs.get(k).collect { case Json.Str(v) => v }
      for (m <- s("method"); p <- s("path"))
        yield Request(m, p, lists(fs.get("query")), lists(fs.get("headers")), s("body").getOrElse(""))
    case _ => None
  }
}
