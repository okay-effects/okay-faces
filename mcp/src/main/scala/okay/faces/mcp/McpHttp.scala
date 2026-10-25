package okay.faces.mcp

import okay.effects.{Effects, Sum}
import okay.effects.json.{Descent, Json}
import okay.http.{Request, Response}
import okay.mcp.{Link, Rpc, Servers}

/**
 * MCP OVER HTTP: the protocol's streamable HTTP transport. `POST path` carries a message or a batch; the answer carries
 * what the server says back — as JSON when the client takes JSON, as a stream of events (`text/event-stream`, finite,
 * closed with the answer) when it accepts one, and then the server's own requests ride in it too: an elicitation,
 * a `list_changed`. `initialize` opens a session named by the `Mcp-Session-Id` header, each session its own server,
 * its own dialogue; `DELETE path` ends it; `GET path` is `405`, no open stream — what the server says, it says in
 * answer to a POST.
 *
 * THE SERVER LOOP IS THE STDIO ONE: `serve` over `Link`, handled here by a request's mail — a receive takes the
 * next message that came in, a send puts a line to go out — and PARKED when the mail is empty: the handler keeps the
 * loop's continuation in the session, the response goes, and the next POST resumes it with what it brings. So an
 * elicitation, which sends a request and then receives, parks until the client's answer arrives: a question to the
 * user crosses HTTP exchanges as it crosses lines on stdio, by the continuation, without a thread kept.
 */
class McpHttp[F <: Effects with Singleton, S <: Servers[F]](val servers: S) {
  import servers.fx._

  /** a request's mail: what came in, what goes out */
  final case class Mail(in: List[String], out: List[String])
  /** the loop's state between requests: ended, or parked at a receive with its continuation */
  sealed trait Parked[Rest[_]]
  final case class Ended[Rest[_]]() extends Parked[Rest]
  final case class At[Rest[_]](resume: (Option[String], Mail) => (Mail, Parked[Rest]) ! Rest) extends Parked[Rest]

  /** `Link` by the mail, parked when the mail is empty — the continuation kept */
  def linked[R[_], Rest[_]](p: Unit ! R, mail: Mail)(implicit sp: Split[Link, R, Rest]): (Mail, Parked[Rest]) ! Rest =
    handle[Link, Mail, R, Rest, Unit, (Mail, Parked[Rest])](p, mail,
      new StatefulHandler[Link, Mail, Rest, Unit, (Mail, Parked[Rest])] {
        def ret(m: Mail, u: Unit) = pure((m, Ended[Rest]()))
        def op[X](m: Mail, op: Link[X], k: (X, Mail) => (Mail, Parked[Rest]) ! Rest) = op match {
          case Link.Send(line, answer) => k(answer(()), m.copy(out = m.out :+ line))
          case Link.Receive(answer) => m.in match {
              case line :: rest => k(answer(Some(line)), m.copy(in = rest))
              case Nil => pure[(Mail, Parked[Rest]), Rest]((m, At[Rest]((line, next) => k(answer(line), next))))
            }
        }
      })

  final class Service[R[_]](open: Boolean => servers.Server[({ type L[X] = Sum[R, Link, X] })#L], path: String,
    ids: () => String = okay.faces.Sessions.random) {
    type RL[X] = Sum[R, Link, X]
    // the sessions, by id: random ids, forgotten when idle an hour, at most a thousand (`Sessions` in core)
    private val sessions = new okay.faces.Sessions[(Boolean, Parked[R])](ids)
    private def messages(body: String): Either[Rpc.Failed, List[String]] = Descent.parse(body) match {
      case Left(e) => Left(Rpc.Failed(Json.Null, Rpc.Code.Parse, e.message))
      case Right(Json.Arr(ms)) => Right(ms.toList.map(m => Json.print(m)))
      case Right(m) => Right(List(Json.print(m)))
    }
    private def streams(req: Request): Boolean = req.header("accept").exists(_.contains("text/event-stream"))
    /** what went out, as the client takes it: events, or the answers as JSON */
    private def reply(out: List[String], stream: Boolean, batch: Boolean, id: String): Response = {
      val r =
        if (stream) Response(200, Map("Content-Type" -> "text/event-stream"),
          out.map(l => s"event: message\ndata: $l\n\n").mkString)
        else {
          val answers = out.flatMap(l => Rpc.decode(l).toOption).collect {
            case a: Rpc.Answer => Rpc.json(a); case f: Rpc.Failed => Rpc.json(f)
          }
          if (answers.isEmpty) Response(202) else Response.json(if (batch) Json.Arr(answers.toVector) else answers.head)
        }
      r.header("Mcp-Session-Id", id)
    }
    private def run(id: String, stream: Boolean, parked: Parked[R], in: List[String]): (Mail, Parked[R]) ! R =
      parked match {
        case At(resume) => in match {
            case first :: rest => resume(Some(first), Mail(rest, Nil))
            case Nil => pure[(Mail, Parked[R]), R]((Mail(Nil, Nil), parked))
          }
        case Ended() => pure[(Mail, Parked[R]), R]((Mail(Nil, Nil), parked))
      }
    def service: Request => Response ! R = req =>
      (req.method, req.path) match {
        case ("POST", p) if p == path =>
          messages(req.body) match {
            case Left(f) => pure[Response, R](Response.json(Rpc.json(f), 400))
            case Right(ms) =>
              val batch = req.body.trim.startsWith("[")
              req.header("mcp-session-id") match {
                case Some(id) => sessions.get(id) match {
                    case None => pure[Response, R](Response.text("no such session", 404))
                    case Some((stream, parked)) => run(id, stream, parked, ms).map { case (mail, next) =>
                        next match {
                          case Ended() => sessions.remove(id); case _ => sessions.update(id, (stream, next))
                        }
                        reply(mail.out, stream, batch, id)
                      }
                  }
                case None
                    if ms.exists(m =>
                      Rpc.decode(m).exists { case Rpc.Request(_, "initialize", _) => true; case _ => false }) =>
                  val stream = streams(req)
                  linked[RL, R](servers.serve[RL](open(stream)), Mail(ms, Nil)).map { case (mail, next) =>
                    val id = sessions.open((stream, next))
                    next match { case Ended() => sessions.remove(id); case _ => () }
                    reply(mail.out, stream, batch, id)
                  }
                case None => pure[Response, R](Response.json(Rpc.json(Rpc.Failed(Json.Null, Rpc.Code.InvalidRequest,
                          "initialize first, then send Mcp-Session-Id")), 400))
              }
          }
        case ("DELETE", p) if p == path =>
          req.header("mcp-session-id").flatMap(id => sessions.remove(id).map(_ => id)) match {
            case Some(_) => pure[Response, R](Response(200))
            case None => pure[Response, R](Response.text("no such session", 404))
          }
        case ("GET", p) if p == path =>
          pure[Response, R](
            Response.text("no open stream: POST the messages, accept text/event-stream for the server's own", 405))
        case _ => pure[Response, R](Response.notFound(req.path))
      }
  }
  /** a server per session, at `path`, in the row with `Link` — its tools may ask (`elicit`); `open` is told whether the
   * client accepts a stream, and only then should a server ask */
  def service[R[_]](open: Boolean => servers.Server[({ type L[X] = Sum[R, Link, X] })#L], path: String = "/mcp",
    ids: () => String = okay.faces.Sessions.random): Service[R] = new Service[R](open, path, ids)
}
object McpHttp {
  /** over these servers, their path kept — an `McpFace`'s, so a `Server` it made is one this carries */
  def apply[F <: Effects with Singleton](servers: Servers[F]): McpHttp[F, servers.type] =
    new McpHttp[F, servers.type](servers)
}
