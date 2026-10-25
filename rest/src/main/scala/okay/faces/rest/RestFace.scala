package okay.faces.rest

import okay.effects.{Effects, Runner}
import okay.effects.tagless.{Program, Tagless}
import okay.effects.json.Json
import okay.effects.shape.JsonSchema
import okay.faces.{Action, Chosen, Face, Faces, Look, Rejected, Session, Sessions, Step, View, ViewJson, Vocabulary}
import okay.http.{Http, Https, Request, Response}
import okay.http.rest.{Link, Resource, Rests, Trouble}

/**
 * THE REST FACE: the dialogue as a JSON API in HATEOAS's manner — a representation with its affordances. `POST
 * /sessions`
 * opens a session and answers its first resource; `GET /sessions/{id}` the current view and the actions as links, each
 * with its method, its href and its parameters' JSON Schema; `POST /sessions/{id}/act/{name}` with the arguments is
 * `resume` — the next resource, or a 400 problem with the step unchanged; `POST /sessions/{id}/back`. Sessions live in
 * memory, by id. Every face adds two things to the step machine: how it shows, how it reads a choice; this one shows
 * JSON and reads JSON. The data carries the actions as affordances too — the exact `Schema`, for a client of ours such
 * as
 * the remote Laminar face; the links carry JSON Schema for everyone else.
 */
class RestFace[F <: Runner with Singleton](implicit val fx: F) {
  import fx._
  val faces: Faces[fx.type] = new Faces[fx.type]()(fx)
  val rests: Rests[fx.type] = new Rests[fx.type]()(fx)
  import rests.{Route, dispatch, get, post}

  /** the sessions as resources: opened, shown, chosen in, gone back in — a service over `Http` */
  final class Resources[A, R[_]](open: () => Session[fx.type, A, R],
    settings: RestFace.Settings = RestFace.Settings()) {
    private val base = settings.base
    private val vocabulary = settings.vocabulary
    // the sessions, by id: random ids, forgotten when idle an hour, at most a thousand (`Sessions`)
    private val sessions = new Sessions[Session[fx.type, A, R]](settings.ids)

    def resource(id: String, d: Session[fx.type, A, R]): Resource ! R = d.view.flatMap(v =>
      d.actions.flatMap(as =>
        d.done.flatMap(over =>
          d.listening.map { listening =>
            val links = as.map(a =>
              Link(a.name, s"$base/$id/act/${a.name}", "POST", Some(a.doc.getOrElse(a.label)),
                Some(JsonSchema.of(a.params.erased)))) ++
              List(Link("self", s"$base/$id")) ++ (if (d.canGoBack) List(Link("back", s"$base/$id/back", "POST"))
                                                   else Nil) ++
              (if (vocabulary.isEmpty) Nil
               else List(Link("marks", s"$base/marks", "GET",
                   Some("the application's marks on views, and what they mean"))))
            Resource(Json.obj("view" -> ViewJson.encode(v), "done" -> Json.Bool(over), "back" -> Json.Bool(d.canGoBack),
                "listening" -> Json.Bool(listening), "actions" -> Json.Arr(as.map(_.affordance.json).toVector)), links)
          })))
    def routes: List[Route[R]] = List(
      get[R](s"$base/marks") { (_, _) =>
        pure[Response, R](if (vocabulary.isEmpty) Trouble(404, "no marks").response else Response.json(vocabulary.json))
      },
      post[R](base) { (_, _) =>
        val d = open(); val id = sessions.open(d); d.moved = () => settings.moved(id)
        d.marked = p => settings.marked(id, p)
        resource(id, d).map(_.response(201).header("Location", s"$base/$id"))
      },
      get[R](s"$base/:id") { (ps, _) =>
        sessions.get(ps("id")).fold(pure[Response, R](Trouble(404, "no such session").response))(d =>
          resource(ps("id"), d).map(_.response()))
      },
      post[R](s"$base/:id/act/:name") { (ps, req) =>
        sessions.get(ps("id")) match {
          case None => pure[Response, R](Trouble(404, "no such session").response)
          case Some(d) =>
            val args = if (req.body.trim.isEmpty) Right(Json.Obj.empty) else req.json
            args match {
              case Left(e) => pure[Response, R](Trouble(400, "the body is not JSON",
                    List(okay.effects.shape.Problem(Nil, e))).response)
              case Right(j) => d.choose(Chosen(ps("name"), j)).flatMap {
                  case Right(_) => resource(ps("id"), d).map(_.response())
                  case Left(Rejected(problems)) => pure[Response, R](Trouble(400, "not taken", problems).response)
                }
            }
        }
      },
      post[R](s"$base/:id/back") { (ps, _) =>
        sessions.get(ps("id")) match {
          case None => pure[Response, R](Trouble(404, "no such session").response)
          case Some(d) => if (d.back()) resource(ps("id"), d).map(_.response())
            else pure[Response, R](Trouble(409, "nothing to go back to").response)
        }
      })
    def service: Request => Response ! R = dispatch[R](routes)
  }
  def sessions[A, R[_]](open: () => Session[fx.type, A, R], settings: RestFace.Settings = RestFace.Settings())
    : Resources[A, R] = new Resources[A, R](open, settings)

  /** THE HANDLER: a program of the face as a JSON API, a service over `Http` — `POST base` opens a session, the
   * resource
   * at `base/{id}` with its links, the marks at `base/marks` — for a server, or the remote face */
  def handle[A](p: Program[A, okay.faces.Face], settings: RestFace.Settings = RestFace.Settings())
    : Request => Response ! H =
    sessions[A, H](() => faces.session[A, H](faces.steps[A, HF, H](faces.of(p).into[HF])), settings).service
  /** the same served: `run(new RestFace().serve(Noughts.games, port, host))`; with a `page`, the thin page at `/` that
   * plays
   * the sessions from the browser — the remote face's script (`okay.faces.laminar.RemotePage`, linked), knowing no
   * program */
  def serve[A](p: Program[A, okay.faces.Face], port: Int = 8080, host: String = "127.0.0.1",
    settings: RestFace.Settings = RestFace.Settings()): Unit ! Pure = {
    val title = Step.opening[A, ({ type M[X] = X ! Pure })#M](faces.steps[A, okay.faces.Face, Pure](faces.of(p)),
      run).title.getOrElse("okay")
    Web.serve(fx, port, host)((List(settings.base -> handle[A](p, settings)) ++ settings.page.map(script =>
        "/" -> new Shell[fx.type]()(fx).handle(title, settings.look, "/" -> script)).toList): _*)
  }
  type H[X] = okay.effects.Sum[Pure, Http, X]
  type HF[X] = okay.effects.Sum[H, Face, X]
}

object RestFace {
  /** THE FACE'S SETTINGS, one value: where the sessions are, the vocabulary of the application's marks (served at
   * `base/marks`), how sessions are named (random by default; a test gives `Sessions.counting("s")`); for `serve`, the
   * thin page at `/` (`page`, the remote face's script, linked) and its look; `moved`: told a session's id when it
   * moved on by itself (an event while it waited) — a host that can push tells its pages; `marked`: told each point
   * a session's program passes (`Durable`) — a host that loses its memory keeps it */
  final case class Settings(base: String = "/sessions", vocabulary: Vocabulary = Vocabulary.empty,
    ids: () => String = Sessions.random, look: Option[Look] = None,
    page: Option[() => Either[String, String]] = None, moved: String => Unit = _ => (),
    marked: (String, Step.Point) => Unit = (_, _) => ())
  /** over the implementation in scope */
  def apply[F <: Runner with Singleton]()(implicit fx: F): RestFace[F] = new RestFace[F]()(fx)
  /** THE HANDLER, whole, served: `run(RestFace.serve(Noughts.games, port, host))` */
  def serve[F <: Runner with Singleton, A](p: Program[A, okay.faces.Face], port: Int = 8080, host: String = "127.0.0.1",
    settings: Settings = Settings())(implicit fx: F): Program[Unit, Tagless.Pure] = {
    val face = new RestFace[F]()(fx)
    Faces.running(face.fx.run[Unit](face.serve[A](p, port, host, settings)))
  }
}
