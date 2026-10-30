// HOW IT LOOKS, as the application says (okay-faces' `Look`): tones, a rule per role, the face's words. The face
// interprets them; it has no colour, size or word of its own. Sizes are `sp`, so the phone's font size scales them.
package okay.faces.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject

data class Rule(
  val color: String? = null, val background: String? = null, val border: String? = null,
  val bold: Boolean = false, val mono: Boolean = false, val centered: Boolean = false,
  val size: Double = 1.0, val pad: Int = 0, val gap: Int = 0, val row: Boolean = false,
) {
  companion object {
    fun of(o: JSONObject) = Rule(o.str("color"), o.str("background"), o.str("border"), o.flag("bold"),
      o.flag("mono"), o.flag("centered"), o.num("size") ?: 1.0, (o.num("pad") ?: 0.0).toInt(),
      (o.num("gap") ?: 0.0).toInt(), o.str("flow") == "row")
  }
}

class LookSpec(o: JSONObject, dark: Boolean) {
  val palette: Map<String, Color>
  val size: Double
  val space: Double
  val serif: Boolean
  val mono: Boolean
  val rules: Map<String, Rule>
  val words: Map<String, String>

  init {
    val theme = o.optJSONObject("theme") ?: JSONObject()
    val night = o.optJSONObject("dark")?.optJSONObject("palette")
    val p = if (dark && night != null) night else theme.optJSONObject("palette") ?: JSONObject()
    palette = p.keys().asSequence().associateWith { color(p.optString(it)) }
    size = theme.num("size") ?: 16.0
    space = theme.num("space") ?: 8.0
    serif = theme.str("font") == "serif"; mono = theme.str("font") == "mono"
    val rs = o.optJSONObject("rules") ?: JSONObject()
    rules = rs.keys().asSequence().associateWith { Rule.of(rs.optJSONObject(it) ?: JSONObject()) }
    val ws = o.optJSONObject("words") ?: JSONObject()
    words = ws.keys().asSequence().associateWith { ws.optString(it) }
  }

  fun rule(role: String): Rule = rules[role] ?: Rule()
  fun tone(t: String?): Color? = t?.let { palette[it] }
  fun word(k: String, otherwise: String): String = words[k] ?: otherwise
  fun gap(role: String) = (rule(role).gap * space).dp
  /** the page's tone: a shade off the cards', as a grouped screen is */
  val page: Color get() {
    val bg = palette["bg"] ?: return Color(0xFFF2F2F7)
    val fg = palette["fg"] ?: return bg
    val k = 0.05f
    fun mix(a: Float, b: Float) = a + (b - a) * k
    return Color(mix(bg.red, fg.red), mix(bg.green, fg.green), mix(bg.blue, fg.blue))
  }
  /** a mark's colour in a picture: its background, else its colour, else the accent */
  fun markColor(mark: String): Color = rule("mark-$mark").let { r -> tone(r.background) ?: tone(r.color) }
    ?: tone("accent") ?: Color(0xFF0969DA)

  companion object {
    val empty = LookSpec(JSONObject(), false)
    fun color(hex: String): Color {
      val v = hex.removePrefix("#").toLongOrNull(16) ?: 0
      return Color(0xFF000000 or v)
    }
  }
}

/** what a part takes from the part around it, as the web's text does: colour, weight, typeface, size as a multiple */
data class Inherited(val color: String? = null, val bold: Boolean = false, val mono: Boolean = false,
  val size: Double = 1.0, val centered: Boolean = false)
val LocalInherited = compositionLocalOf { Inherited() }

/** A PART DRESSED BY ITS ROLES' RULES (general to specific): its text, its tones, its padding, its border */
@Composable
fun Dressed(look: LookSpec, vararg roles: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
  val around = LocalInherited.current
  val r = roles.map { look.rule(it) }.fold(Rule()) { acc, x ->
    acc.copy(color = x.color ?: acc.color, background = x.background ?: acc.background,
      border = x.border ?: acc.border, bold = acc.bold || x.bold, mono = acc.mono || x.mono,
      centered = acc.centered || x.centered, size = if (x.size != 1.0) x.size else acc.size,
      pad = if (x.pad != 0) x.pad else acc.pad, gap = if (x.gap != 0) x.gap else acc.gap, row = acc.row || x.row)
  }
  val inner = Inherited(r.color ?: around.color, r.bold || around.bold, r.mono || around.mono, r.size * around.size,
    r.centered || around.centered)
  val shape = RoundedCornerShape(if (r.pad > 0) 8.dp else 0.dp)
  var m = modifier
  if (r.centered) m = m.fillMaxWidth()
  look.tone(r.background)?.let { m = m.background(it, shape) }
  look.tone(r.border)?.let { m = m.border(1.dp, it, RoundedCornerShape(8.dp)) }
  m = m.padding((r.pad * look.space).dp)
  val style = LocalTextStyle.current.copy(
    fontSize = (look.size * inner.size).sp,
    fontWeight = if (inner.bold) FontWeight.Bold else FontWeight.Normal,
    fontFamily = if (inner.mono || look.mono) FontFamily.Monospace else if (look.serif) FontFamily.Serif
      else FontFamily.Default,
    textAlign = if (inner.centered) TextAlign.Center else TextAlign.Start)
  CompositionLocalProvider(LocalInherited provides inner, LocalTextStyle provides style,
    LocalContentColor provides (look.tone(inner.color) ?: look.tone("fg") ?: Color.Black)) {
    Box(m, contentAlignment = if (r.centered) Alignment.Center else Alignment.TopStart) { content() }
  }
}
