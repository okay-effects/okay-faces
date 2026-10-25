package okay.faces.mcp

import okay.effects.Runner
import okay.effects.tagless.{Program, Tagless}
import okay.effects.json.Json
import okay.effects.shape.{JsonSchema, Schema}
import okay.effects.describe.{Describe, Domains}
import okay.faces.{Action, Chosen, Face, Faces, Rejected, Step, View, ViewJson, Vocabulary}
import okay.mcp.{Content, Link, ResourceContent, ResourceSpec, Servers, ToolCall, ToolResult, ToolSpec}

/**
 * THE MCP FACE: the dialogue projection. The agent sees, at each step, exactly the actions a person would — as tools,
 * each with its parameters' JSON Schema — plus `look` (the current view) and `back`. A tool call is `resume`; the
 * result is the next view, as Markdown text and as structured content; a rejected call is an error result and the step
 * is unchanged. The view is a resource too. The step machine's rest runs by the `Runner`: the row is done before the
 * face, but for `Link`, which the server's loop needs (and `Consent` by elicitation performs).
 */
class McpFace[F <: Runner with Singleton](implicit val fx: F) {
  import fx._
  val faces: Faces[fx.type] = new Faces[fx.type]()(fx)
  /** the server over the SAME implementation value, so one `!` serves both */
  val servers: Servers[fx.type] = new Servers[fx.type]()(fx)

  type Session[A, R[_]] = okay.faces.Session[fx.type, A, R]
  /** the same servers over HTTP: `http.service(() => server(...))` */
  val http: McpHttp[fx.type, servers.type] = new McpHttp[fx.type, servers.type](servers)
  val domains: Domains[fx.type] = new Domains[fx.type]()(fx)

  /** the dialogue as tools: the step's actions, `look`, and `back` when there is a step to go back to — the list
   * changes as the dialogue moves, and `list_changed` says so */
  def tools[A, R[_]](d: Session[A, R], vocabulary: Vocabulary = Vocabulary.empty): servers.Tools[R] =
    new servers.Tools[R] {
      def list: List[ToolSpec] ! R = d.actions.map(as =>
        as.map(a => ToolSpec(a.name, a.doc.getOrElse(a.label), JsonSchema.of(a.params.erased))) ++ List(
          ToolSpec("look", "the current view, as it stands", Json.obj("type" -> Json.Str("object")))) ++
          (if (d.canGoBack) List(ToolSpec("back", "one step back: the previous view and its actions",
               Json.obj("type" -> Json.Str("object"))))
           else Nil))
      def call(c: ToolCall): ToolResult ! R = c.name match {
        case "look" => d.view.flatMap(v => d.actions.map(as => shown(v, as, vocabulary)))
        case "back" => if (d.back()) d.view.flatMap(v => d.actions.map(as => shown(v, as, vocabulary)))
          else pure[ToolResult, R](ToolResult.error("nothing to go back to"))
        case name => d.choose(Chosen(name, c.arguments)).flatMap {
            case Right(view) => d.actions.map(as => shown(view, as, vocabulary))
            case Left(Rejected(problems)) => pure[ToolResult, R](ToolResult(
                  List(Content.Text("not taken:\n" + problems.map(p => "- " + p.render).mkString("\n"))),
                  Some(Json.obj("problems" -> Json.Arr(problems.map(p =>
                        Json.obj("path" -> Json.Arr(p.path.map(Json.Str(_)).toVector),
                          "message" -> Json.Str(p.message))).toVector))), isError = true))
          }
      }
    }
  /** THE SINGLE-TOOL FALLBACK, for a client that reads the tool list once: `act(action, arguments)` is every step's
   * action, the current ones and their schemas in each result's text and in the `look`'s; `look` and `back` as ever */
  def single[A, R[_]](d: Session[A, R], vocabulary: Vocabulary = Vocabulary.empty): servers.Tools[R] =
    new servers.Tools[R] {
      private def menu(as: List[Action[_]]): String = if (as.isEmpty) "no actions: the dialogue is over"
      else
        "actions:\n" + as.map { a =>
          val arguments = Json.print(JsonSchema.of(a.params.erased))
          s"- ${a.name}: ${a.doc.getOrElse(a.label)}; arguments $arguments"
        }.mkString("\n")
      private def told(view: View, as: List[Action[_]]): ToolResult =
        ToolResult(List(Content.Text(Markdown.view(view, vocabulary) + "\n\n" + menu(as))),
          Some(Json.obj("view" -> ViewJson.encode(view), "actions" -> Json.Arr(as.map(_.affordance.json).toVector))))
      def list: List[ToolSpec] ! R = pure[List[ToolSpec], R](List(
        ToolSpec("act", "one of the current actions, with its arguments — `look` says which, and every result does",
          Json.obj("type" -> Json.Str("object"),
            "properties" -> Json.obj("action" -> Json.obj("type" -> Json.Str("string")),
              "arguments" -> Json.obj("type" -> Json.Str("object"))), "required" -> Json.arr(Json.Str("action")))),
        ToolSpec("look", "the current view and the actions, as they stand", Json.obj("type" -> Json.Str("object")))) ++
        // the list never changes in this mode (for a client that does not follow `list_changed`): `back` is always
        // there, and answers "nothing to go back to" when no step behind was kept
        List(ToolSpec("back", "one step back", Json.obj("type" -> Json.Str("object")))))
      def call(c: ToolCall): ToolResult ! R = c.name match {
        case "look" => d.view.flatMap(v => d.actions.map(as => told(v, as)))
        case "back" => if (d.back()) d.view.flatMap(v => d.actions.map(as => told(v, as)))
          else pure[ToolResult, R](ToolResult.error("nothing to go back to"))
        case "act" => c.arguments match {
            case o: Json.Obj => o.get("action") match {
                case Some(Json.Str(name)) =>
                  d.choose(Chosen(name, o.get("arguments").getOrElse(Json.Obj.empty))).flatMap {
                    case Right(view) => d.actions.map(as => told(view, as))
                    case Left(Rejected(problems)) => d.actions.map(as =>
                        ToolResult(List(Content.Text("not taken:\n" + problems.map(p => "- " + p.render).mkString(
                              "\n") + "\n\n" + menu(as))), None, isError = true))
                  }
                case _ => pure[ToolResult, R](ToolResult.error("act takes an action's name"))
              }
            case _ => pure[ToolResult, R](ToolResult.error("act takes an object with action and arguments"))
          }
        case other => pure[ToolResult, R](ToolResult.error(s"no tool $other: act, look, back"))
      }
    }
  private def shown(view: View, actions: List[Action[_]], vocabulary: Vocabulary): ToolResult =
    ToolResult(List(Content.Text(Markdown.view(view,
          vocabulary) + (if (actions.isEmpty) "" else "\n\nactions: " + actions.map(_.name).mkString(", ")))),
      Some(Json.obj("view" -> ViewJson.encode(view),
          "actions" -> Json.Arr(actions.map(a => Json.Str(a.name)).toVector))))

  /** THE DOMAIN PROJECTION: a described effect's operations as tools, `{domain}_{op}`, each with its parameters' JSON
   * Schema and its result's; a call performs the operation under the handler in the row and answers the result as JSON.
   * No session, no view */
  def domain[E[_], R[_]](d: Describe[E])(implicit in: In[E, R]): servers.Tools[R] = new servers.Tools[R] {
    def list: List[ToolSpec] ! R = pure[List[ToolSpec], R](d.operations.map(o =>
        ToolSpec(s"${d.name}_${o.name}", o.doc.getOrElse(o.name), JsonSchema.of(o.params.erased),
          Some(JsonSchema.of(o.result.erased)))))
    def call(c: ToolCall): ToolResult ! R = domains.serve[E, R](d)(c.name.stripPrefix(d.name + "_"), c.arguments).map {
      case Right(j) => ToolResult(List(Content.Text(Json.pretty(j))), Some(j))
      case Left(ps) => ToolResult(
          List(Content.Text("not taken:\n" + ps.map(p => "- " + p.render).mkString("\n"))), None, isError = true)
    }
  }
  /** two sets of tools as one: a call goes to the first that lists its name */
  def both[R[_]](a: servers.Tools[R], b: servers.Tools[R]): servers.Tools[R] = new servers.Tools[R] {
    def list: List[ToolSpec] ! R = a.list.flatMap(as => b.list.map(as ++ _))
    def call(c: ToolCall): ToolResult ! R =
      a.list.flatMap(as => if (as.exists(_.name == c.name)) a.call(c) else b.call(c))
  }

  /** the current view as a resource, Markdown and JSON; the application's marks at `face://marks` when it has a
   * vocabulary */
  def resources[A, R[_]](d: Session[A, R], vocabulary: Vocabulary = Vocabulary.empty): servers.Resources[R] =
    new servers.Resources[R] {
      def list: List[ResourceSpec] ! R = pure[List[ResourceSpec], R](List(
        ResourceSpec("face://view", "the current view", Some("text/markdown")),
        ResourceSpec("face://view.json", "the current view, structured", Some("application/json"))) ++
        (if (vocabulary.isEmpty) Nil
         else List(ResourceSpec("face://marks", "the application's marks on views, and what they mean",
             Some("application/json")))))
      def read(uri: String): List[ResourceContent] ! R = d.view.map(v =>
        uri match {
          case "face://view" => List(ResourceContent(uri, "text/markdown", Markdown.view(v, vocabulary)))
          case "face://view.json" => List(ResourceContent(uri, "application/json", Json.print(ViewJson.encode(v))))
          case "face://marks" if !vocabulary.isEmpty =>
            List(ResourceContent(uri, "application/json", Json.print(vocabulary.json)))
          case _ => Nil
        })
    }

  /** THE HANDLER: a program of the face served to an agent — the server's loop over `Link`, for a link handler: a
   * scripted
   * one in a test, standard input and output by `stdio`. Named by the first level-1 heading the program shows,
   * instructed
   * by what it shows before its first offer and by the `vocabulary` of its marks */
  def handle[A](p: Program[A, okay.faces.Face], settings: McpFace.Settings = McpFace.Settings()): Unit ! L = {
    val local = faces.of(p)
    val opening = Step.opening[A, ({ type M[X] = X ! Pure })#M](faces.steps[A, okay.faces.Face, Pure](local), run)
    val instructions = Some(opening.intro.map(Markdown.view(_, settings.vocabulary)).filter(_.nonEmpty)
        .mkString("\n\n")).filter(_.nonEmpty)
    servers.serve[L](server[A, L](opening.title.getOrElse("okay"), faces.steps[A, LF, L](local.into[LF]),
        instructions, settings))
  }
  /** the same over standard input and output, the transport an agent's client starts a server with: `run(new
   * McpFace().stdio(Noughts.games))` */
  def stdio[A](p: Program[A, okay.faces.Face], settings: McpFace.Settings = McpFace.Settings()): Unit ! Pure =
    servers.links.stdio[L, Pure, Unit](handle[A](p, settings))
  type L[X] = okay.effects.Sum[Pure, Link, X]
  type LF[X] = okay.effects.Sum[L, Face, X]

  /** THE HANDLER OVER HTTP: the program served to agents by MCP's streamable HTTP at `path` (`McpHttp`), a server — a
   * dialogue — per session, its sessions named by `ids`: a service over `Http`, for a server or a host's door. An
   * elicitation only to a client that takes a stream */
  def served[A](p: Program[A, okay.faces.Face], settings: McpFace.Settings = McpFace.Settings(),
    path: String = "/mcp", ids: () => String = okay.faces.Sessions.random, marked: Step.Point => Unit = _ => ())
    : okay.http.Request => okay.http.Response ! H = {
    val local = faces.of(p)
    val opening = Step.opening[A, ({ type M[X] = X ! Pure })#M](faces.steps[A, okay.faces.Face, Pure](local), run)
    val instructions = Some(opening.intro.map(Markdown.view(_, settings.vocabulary)).filter(_.nonEmpty)
        .mkString("\n\n")).filter(_.nonEmpty)
    http.service[H](stream => server[A, HL](opening.title.getOrElse("okay"), faces.steps[A, HLF, HL](local.into[HLF]),
        instructions, settings.copy(elicitation = settings.elicitation && stream), marked), path, ids).service
  }
  type H[X] = okay.effects.Sum[Pure, okay.http.Http, X]
  type HL[X] = okay.effects.Sum[H, Link, X]
  type HLF[X] = okay.effects.Sum[HL, Face, X]

  /** a server of the dialogue, named; `first` the steps over the server's own row; the `vocabulary` of the
   * application's
   * marks is told in the instructions, served at `face://marks`, and shown in the Markdown */
  def server[A, R[_]](name: String, first: Step[A, ({ type L[X] = X ! R })#L] ! R,
    instructions: Option[String] = None, settings: McpFace.Settings = McpFace.Settings(),
    marked: Step.Point => Unit = _ => ()): servers.Server[R] = {
    import settings.{elicitation, singleTool, version, vocabulary}
    val d = faces.session[A, R](first)
    d.marked = marked
    val told = if (vocabulary.isEmpty) instructions
    else Some((instructions.toList :+ ("Marks on views, and what they mean:\n" + vocabulary.markdown)).mkString("\n\n"))
    servers.Server[R](name, version, if (singleTool) single[A, R](d, vocabulary) else tools[A, R](d, vocabulary),
      Some(resources[A, R](d, vocabulary)), told, elicitation)
  }
}

/** a `View` as Markdown; a mark the `vocabulary` knows is shown — `**price:** 12` — one it does not is seen through */
object Markdown {
  def view(v: View): String = view(v, Vocabulary.empty)
  def oneLine(v: View): String = oneLine(v, Vocabulary.empty)
  def view(v: View, vocabulary: Vocabulary): String = {
    def line(x: View): String = oneLine(x, vocabulary)
    v match {
      case View.Empty => ""
      case View.Text(s) => s
      case View.Heading(level, s) => "#" * level + " " + s
      case View.Notice(level, s) => s"**${level.toString.toLowerCase}:** $s"
      case View.Fields(rows) => rows.map { case (k, x) => s"- **$k:** ${line(x)}" }.mkString("\n")
      case View.Table(columns, rows) =>
        val header = if (columns.forall(_.isEmpty)) columns.indices.map(i => s"c${i + 1}") else columns
        (List(header.mkString("| ", " | ", " |"), header.map(_ => "---").mkString("| ", " | ", " |")) ++ rows.map(
          _.map(line).mkString("| ", " | ", " |"))).mkString("\n")
      case View.Items(items) => items.map(i => "- " + line(i)).mkString("\n")
      case View.Group(title, children) =>
        (title.map("### " + _).toList ++ children.map(view(_, vocabulary)).filter(_.nonEmpty)).mkString("\n\n")
      case View.Ref(key, label) => s"[$label](#$key)"
      case View.Link(label, url) => s"[$label]($url)"
      case View.Datum(json, _) => "```json\n" + Json.pretty(json) + "\n```"
      case View.Masked(hint) => hint
      case View.Picture(_, _, _, text) => text
      case View.Marked(m, x) if vocabulary.has(m) =>
        x match {
          case View.Text(_) | View.Masked(_) | View.Ref(_, _) | View.Link(_, _) | View.Heading(_, _) =>
            s"**$m:** ${line(x)}"
          case _ => s"**$m**\n\n" + view(x, vocabulary)
        }
      case View.Marked(_, x) => view(x, vocabulary)
    }
  }
  def oneLine(v: View, vocabulary: Vocabulary): String = {
    def line(x: View): String = oneLine(x, vocabulary)
    v match {
      case View.Marked(m, x) if vocabulary.has(m) => s"$m: ${line(x)}"
      case View.Marked(_, x) => line(x)
      case View.Fields(rows) => rows.map { case (k, x) => s"$k: ${line(x)}" }.mkString(", ")
      case View.Table(_, rows) => rows.map(_.map(line).mkString(" ")).mkString(" / ")
      case View.Items(items) => items.map(line).mkString(", ")
      case View.Group(t, cs) => (t.toList ++ cs.map(line)).mkString(" ")
      case View.Datum(json, _) => Json.print(json)
      case View.Heading(_, s) => s
      case other => view(other, vocabulary)
    }
  }
}

object McpFace {
  /** THE FACE'S SETTINGS, one value: the vocabulary of the application's marks (told in the instructions, served at
   * `face://marks`), the server's version, whether consent is asked by elicitation, whether the actions are one tool
   * `act` (`singleTool`) rather than a tool each */
  final case class Settings(vocabulary: Vocabulary = Vocabulary.empty, version: String = "1",
    elicitation: Boolean = false, singleTool: Boolean = false)
  /** over the implementation in scope */
  def apply[F <: Runner with Singleton]()(implicit fx: F): McpFace[F] = new McpFace[F]()(fx)
  /** THE HANDLER, whole, over standard input and output: `run(McpFace.stdio(Noughts.games))` */
  def stdio[F <: Runner with Singleton, A](p: Program[A, okay.faces.Face], settings: Settings = Settings())(
    implicit fx: F): Program[Unit, Tagless.Pure] = {
    val face = new McpFace[F]()(fx)
    Faces.running(face.fx.run[Unit](face.stdio[A](p, settings)))
  }
}
