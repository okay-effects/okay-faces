package okay.faces.cloudflare

import okay.effects.{Effects, Runner}
import okay.effects.json.Json
import okay.effects.tagless.Program
import okay.faces.html.HtmlFace
import okay.faces.rest.RestFace
import okay.http.{Https, Request, Response}

/**
 * A DOOR OF THE APPLICATION: where its sessions are (`base/{id}`), which request opens one, the service of one session
 * by its id — a face's handler, run (`Door.html`, `Door.rest`).
 */
final case class Door(base: String, opens: Request => Boolean, session: String => Request => Response) {
  /** the session a request is of, if it is of one: the path's segment after the base */
  def idOf(req: Request): Option[String] =
    if (!req.path.startsWith(base.stripSuffix("/") + "/")) None
    else req.path.substring(base.stripSuffix("/").length + 1).split('/').headOption.filter(_.nonEmpty)
}

object Door {
  /** the program as pages from the server (the HTML face): `/` and `base` open a session. The program made again for
   * each session (by name): a handler it holds (a ledger in memory) is the session's own, and its journal's */
  def html[F <: Runner with Singleton, A](p: => Program[A, okay.faces.Face],
    settings: HtmlFace.Settings = HtmlFace.Settings())(implicit fx: F): Door = {
    val face = new HtmlFace[F]()(fx)
    val https = new Https[face.fx.type]()(face.fx)
    Door(settings.base, r => r.method == "GET" && (r.path == "/" || r.path == settings.base), { id =>
      val service = face.handle[A](p, settings.copy(ids = () => id))
      req => face.fx.run(https.scripted(Nil).apply[face.H, face.fx.Pure, Response](service(req))(
        face.fx.splitLast[face.fx.Pure, okay.http.Http]))._2
    })
  }

  /** the program as a JSON API with links (the REST face; the thin page's): `POST base` opens a session; the program
   * made again for each session, as `html`'s */
  def rest[F <: Runner with Singleton, A](p: => Program[A, okay.faces.Face],
    settings: RestFace.Settings = RestFace.Settings())(implicit fx: F): Door = {
    val face = new RestFace[F]()(fx)
    val https = new Https[face.fx.type]()(face.fx)
    Door(settings.base, r => r.method == "POST" && r.path == settings.base, { id =>
      val service = face.handle[A](p, settings.copy(ids = () => id))
      req => face.fx.run(https.scripted(Nil).apply[face.H, face.fx.Pure, Response](service(req))(
        face.fx.splitLast[face.fx.Pure, okay.http.Http]))._2
    })
  }
}

/** A SESSION'S JOURNAL, the host's effect: the requests that changed it, read; one more appended */
sealed trait Kept[X]
object Kept {
  final case class Journal[X](answer: List[Request] => X) extends Kept[X]
  final case class Append[X](request: Request, answer: Unit => X) extends Kept[X]
}

/** a session in a host's memory: its service, while the host is awake */
final class Held {
  var service: Option[Request => Response] = None
}

/**
 * A SESSION HOSTED WHERE MEMORY IS LOST (specs/cloudflare.md): a request answered by the session's service — as it is,
 * or, the host new, a new one with its journal played through it, so it is where it was (the program deterministic,
 * the session's id the same); what changed it — the request that opened it, each one but a read, unless refused —
 * appended to the journal. Platform-neutral: a Durable Object handles `Kept` by its storage, a test by a list.
 */
class Hosting[F <: Effects with Singleton](implicit implementation: F) {
  val fx: F = implementation
  import fx._

  def journal[R[_]](implicit in: In[Kept, R]): List[Request] ! R =
    perform[Kept].apply[R, List[Request]](Kept.Journal(rs => rs))
  def append[R[_]](r: Request)(implicit in: In[Kept, R]): Unit ! R =
    perform[Kept].apply[R, Unit](Kept.Append(r, u => u))

  def answer[R[_]](door: Door, id: String, held: Held, req: Request, opens: Boolean)(
    implicit in: In[Kept, R]): Response ! R = {
    val woken: (Request => Response) ! R = held.service match {
      case Some(s) => pure[Request => Response, R](s)
      case None => journal[R].map { rs =>
          val s = door.session(id)
          rs.foreach(r => s(r))
          held.service = Some(s)
          s
        }
    }
    woken.flatMap { s =>
      val response = s(req)
      if ((opens || req.method != "GET") && response.status < 400) append[R](Hosting.kept(req)).map(_ => response)
      else pure[Response, R](response)
    }
  }

  /** THE JOURNAL GIVEN: read as `journal`, what is appended collected — a host's storage read before, written after */
  def kept[R[_], Rest[_], A](journal: List[Request])(p: A ! R)(implicit sp: Split[Kept, R, Rest])
    : (List[Request], A) ! Rest =
    handle[Kept, List[Request], R, Rest, A, (List[Request], A)](p, Nil,
      new StatefulTailHandler[Kept, List[Request], Rest, A, (List[Request], A)] {
        def ret(appended: List[Request], a: A) = pure((appended.reverse, a))
        def answer[X](cell: Cell[List[Request]], op: Kept[X]): X = op match {
          case Kept.Journal(answer) => answer(journal)
          case Kept.Append(r, answer) => cell.set(r :: cell.get); answer(())
        }
      })
}

object Hosting {
  /** a request as kept: what a face reads of it — its method, path, query, body and its body's type */
  def kept(r: Request): Request =
    r.copy(headers = r.header("content-type").map(t => Map("content-type" -> List(t))).getOrElse(Map.empty))

  def json(r: Request): Json = Json.obj("method" -> Json.Str(r.method), "path" -> Json.Str(r.path),
    "query" -> Json.Obj(r.query.toVector.map { case (k, vs) => k -> Json.Arr(vs.toVector.map(Json.Str(_))) }),
    "type" -> Json.Str(r.header("content-type").getOrElse("")), "body" -> Json.Str(r.body))

  def fromJson(j: Json): Option[Request] = j match {
    case o: Json.Obj =>
      val fs = o.fields.toMap
      def s(k: String) = fs.get(k).collect { case Json.Str(v) => v }
      for (m <- s("method"); p <- s("path")) yield Request(m, p,
        fs.get("query").collect { case q: Json.Obj => q.fields.toMap.map { case (k, v) =>
          k -> (v match { case Json.Arr(xs) => xs.toList.collect { case Json.Str(x) => x }; case _ => Nil }) } }
          .getOrElse(Map.empty),
        s("type").filter(_.nonEmpty).map(t => Map("content-type" -> List(t))).getOrElse(Map.empty),
        s("body").getOrElse(""))
    case _ => None
  }
}
