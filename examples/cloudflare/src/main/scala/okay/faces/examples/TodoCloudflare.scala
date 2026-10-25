package okay.faces.examples

import okay.effects.machine.Machine.self
import okay.faces.cloudflare.{Cloudflare, Door}
import okay.faces.html.HtmlFace
import okay.faces.mcp.McpFace
import okay.faces.rest.RestFace

/** THE LIST ON CLOUDFLARE, one line as its other mains: the thin page at `/` (pushed by its session's socket), its
 * pages at `/s`, its API at `/sessions`, its tools for an agent at `/mcp` — each session a Durable Object; the same
 * program as `TodoHtml`, `TodoWebRemote` and `TodoMcp` serve on the JVM */
object TodoCloudflare {
  def main(args: Array[String]): Unit = Cloudflare.serve(
    Door.shell("To do", Cloudflare.asset("/index.js"), Some(Todo.look)),
    Door.html(Todo.keeping, HtmlFace.Settings(look = Some(Todo.look)), atRoot = false, from = Todo.durable.from),
    Door.rest(Todo.keeping, RestFace.Settings(vocabulary = Todo.marks.vocabulary), from = Todo.durable.from),
    Door.mcp(Todo.keeping, McpFace.Settings(vocabulary = Todo.marks.vocabulary), from = Todo.durable.from))
}
