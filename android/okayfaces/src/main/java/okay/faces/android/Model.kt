// What crosses from the application's Scala: a screen, its views, its actions' fields, the look (specs/mobile.md).
// Read from JSON as it comes; nothing here knows an application. The same reading as the iOS face's Model.swift.
package okay.faces.android

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) optString(k) else null
internal fun JSONObject.num(k: String): Double? = if (has(k) && !isNull(k)) optDouble(k) else null
internal fun JSONObject.flag(k: String): Boolean = optBoolean(k, false)
internal fun JSONObject.objs(k: String): List<JSONObject> = optJSONArray(k)?.let { a ->
  (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
internal fun JSONObject.strs(k: String): List<String> = optJSONArray(k)?.let { a ->
  (0 until a.length()).map { a.optString(it) } } ?: emptyList()

/** A VIEW, as `ViewJson` writes it */
sealed class Node {
  object Empty : Node()
  data class Text(val s: String) : Node()
  data class Heading(val level: Int, val s: String) : Node()
  data class Notice(val level: String, val s: String) : Node()
  data class Fields(val rows: List<Pair<String, Node>>) : Node()
  data class Table(val columns: List<String>, val rows: List<List<Node>>) : Node()
  data class Items(val items: List<Node>) : Node()
  data class Group(val title: String?, val children: List<Node>) : Node()
  data class Ref(val key: String, val label: String) : Node()
  data class Link(val label: String, val url: String) : Node()
  data class Datum(val text: String) : Node()
  data class Masked(val hint: String) : Node()
  data class Image(val source: String, val alt: String) : Node()
  data class Marked(val mark: String, val view: Node) : Node()
  data class Picture(val width: Double, val height: Double, val text: String, val shapes: List<JSONObject>) : Node()

  companion object {
    fun of(o: JSONObject?): Node {
      if (o == null) return Empty
      return when (o.str("type")) {
        "text" -> Text(o.str("text") ?: "")
        "heading" -> Heading((o.num("level") ?: 1.0).toInt(), o.str("text") ?: "")
        "notice" -> Notice(o.str("level") ?: "info", o.str("text") ?: "")
        "fields" -> Fields(o.objs("rows").map { (it.str("label") ?: "") to of(it.optJSONObject("value")) })
        "table" -> Table(o.strs("columns"), o.optJSONArray("rows")?.let { rs ->
          (0 until rs.length()).map { i ->
            rs.optJSONArray(i)?.let { r -> (0 until r.length()).map { of(r.optJSONObject(it)) } } ?: emptyList()
          } } ?: emptyList())
        "items" -> Items(o.objs("items").map { of(it) })
        "group" -> Group(o.str("title"), o.objs("children").map { of(it) })
        "ref" -> Ref(o.str("key") ?: "", o.str("label") ?: "")
        "link" -> Link(o.str("label") ?: "", o.str("url") ?: "")
        "datum" -> Datum(o.opt("value")?.let { if (it is JSONObject) it.toString(2) else it.toString() } ?: "")
        "masked" -> Masked(o.str("hint") ?: "")
        "image" -> Image(o.str("source") ?: "", o.str("alt") ?: "")
        "marked" -> Marked(o.str("mark") ?: "", of(o.optJSONObject("view")))
        "picture" -> Picture(o.num("width") ?: 1.0, o.num("height") ?: 1.0, o.str("text") ?: "", o.objs("shapes"))
        else -> Empty
      }
    }
  }
}

/** ONE FIELD OF A FORM, lowered by the library: its dotted name, a kind a control is chosen by */
data class Field(
  val name: String, val kind: String, val label: String, val doc: String?, val default: String?,
  val required: Boolean, val more: Boolean, val options: List<String>, val fields: List<Field>,
  val cases: List<Pair<String, List<Field>>>, val item: List<Field>, val min: Int, val max: Int?,
) {
  companion object {
    fun of(o: JSONObject): Field = Field(
      o.str("name") ?: "", o.str("kind") ?: "text", o.str("label") ?: "", o.str("doc"), o.str("default"),
      o.flag("required"), o.flag("more"), o.strs("options"), o.objs("fields").map { of(it) },
      o.objs("cases").map { (it.str("name") ?: "") to it.objs("fields").map { f -> of(f) } },
      o.objs("item").map { of(it) }, (o.num("min") ?: 0.0).toInt(), o.num("max")?.toInt())
  }
}

/** ONE ACTION: a button, or a form of fields */
data class ActionSpec(val name: String, val label: String, val doc: String?, val fields: List<Field>) {
  companion object {
    fun of(o: JSONObject) = ActionSpec(o.str("name") ?: "", o.str("label") ?: "", o.str("doc"),
      o.objs("fields").map { Field.of(it) })
  }
}

/** THE SCREEN: what was told, the step's view, its actions, the last choice's problems */
data class Screen(
  val title: String?, val told: List<Node>, val view: Node, val actions: List<ActionSpec>,
  val problems: List<String>, val back: Boolean, val done: Boolean, val error: String?,
) {
  /** whether a `Ref` is live: the action `ref` is offered */
  val refs: Boolean get() = actions.any { it.name == "ref" }

  companion object {
    val empty = Screen(null, emptyList(), Node.Empty, emptyList(), emptyList(), false, false, null)
    fun of(text: String): Screen {
      val o = try { JSONObject(text) } catch (e: Exception) { return empty.copy(error = "not JSON: $text") }
      return Screen(o.str("title"), o.objs("told").map { Node.of(it) }, Node.of(o.optJSONObject("view")),
        o.objs("actions").map { ActionSpec.of(it) },
        o.objs("problems").map { p ->
          val path = p.strs("path").joinToString(".")
          val m = p.str("message") ?: ""
          if (path.isEmpty()) m else "$path: $m"
        }, o.flag("back"), o.flag("done"), o.str("error"))
    }
  }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
