package okay.faces.examples

import okay.effects.machine.Machine.self
import okay.faces.cloudflare.{Cloudflare, Door}
import okay.faces.html.HtmlFace
import okay.faces.rest.RestFace

/** THE LIST ON CLOUDFLARE, one line as its other mains: its pages at `/s`, its API at `/sessions`, each session a
 * Durable Object — the same program as `TodoHtml` and `TodoWebRemote` serve on the JVM */
object TodoCloudflare {
  def main(args: Array[String]): Unit = Cloudflare.serve(
    Door.html(Todo.keeping, HtmlFace.Settings(look = Some(Todo.look))),
    Door.rest(Todo.keeping, RestFace.Settings(vocabulary = Todo.marks.vocabulary)))
}
