// THE ACTIONS: a button each, or a form of the fields the library lowered — values kept by dotted name and sent as
// they are; the library reads them back along the action's schema (specs/mobile.md). The iOS face's, in Compose.
package okay.faces.android

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

@Composable
fun Actions(actions: List<ActionSpec>, look: LookSpec, dialogue: Dialogue, enabled: Boolean) {
  val forms = actions.filter { it.fields.isNotEmpty() }
  val buttons = actions.filter { it.fields.isEmpty() }
  Column(verticalArrangement = Arrangement.spacedBy((look.space * 2).dp)) {
    forms.forEach { FormView(it, look, dialogue, enabled) }
    // one main action a screen: the form's, else the first button; the others lighter
    Column(verticalArrangement = Arrangement.spacedBy(look.space.dp)) {
      buttons.forEachIndexed { i, a ->
        Main(a.label, look, forms.isEmpty() && i == 0, enabled, Modifier.testTag("act-${a.name}")) {
          dialogue.act(a.name) }
      }
    }
  }
}

/** a button as a phone draws a main one: large, the width there is, filled with the action's tone */
@Composable
fun Main(label: String, look: LookSpec, main: Boolean, enabled: Boolean, modifier: Modifier, act: () -> Unit) {
  val haptic = LocalHapticFeedback.current
  val focus = LocalFocusManager.current
  val r = look.rule("action")
  val tone = look.tone(r.background) ?: look.tone("accent") ?: Color(0xFF0969DA)
  val click = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); focus.clearFocus(); act() }
  val shape = RoundedCornerShape(12.dp)
  val m = modifier.fillMaxWidth().heightIn(min = 52.dp)
  val text = @Composable { Text(label, fontSize = (look.size * r.size).sp, fontWeight = FontWeight.Bold) }
  if (main) Button(click, m, enabled, shape, ButtonDefaults.buttonColors(containerColor = tone,
    contentColor = look.tone(r.color) ?: Color.White)) { text() }
  else FilledTonalButton(click, m, enabled, shape, ButtonDefaults.filledTonalButtonColors(
    containerColor = tone.copy(alpha = 0.18f), contentColor = tone)) { text() }
}

/** one action's form: its fields, those held back under the look's "Options", the button */
@Composable
fun FormView(action: ActionSpec, look: LookSpec, dialogue: Dialogue, enabled: Boolean) {
  val values = remember { mutableStateMapOf<String, List<String>>().apply { defaults(action.fields, this) } }
  val counts = remember { mutableStateMapOf<String, Int>() }
  var open by remember { mutableStateOf(false) }
  Card(look) {
    Column(verticalArrangement = Arrangement.spacedBy((look.space * 1.5).dp)) {
      Text(action.label.uppercase(), fontSize = (look.size * 0.8).sp, fontWeight = FontWeight.SemiBold,
        color = look.tone("muted") ?: Color.Gray)
      action.doc?.let { Dressed(look, "doc") { Text(it) } }
      action.fields.filter { !it.more }.forEach { Control(it, null, look, values, counts) }
      val held = action.fields.filter { it.more }
      if (held.isNotEmpty()) {
        TextButton(onClick = { open = !open }) { Text(look.word("options", "Options") + if (open) " ▴" else " ▾") }
        if (open) held.forEach { Control(it, null, look, values, counts) }
      }
      Main(action.label, look, true, enabled, Modifier.testTag("act-${action.name}")) {
        dialogue.act(action.name, values.toMap()) }
    }
  }
}

private fun defaults(fs: List<Field>, values: MutableMap<String, List<String>>) {
  for (f in fs) {
    if (f.default != null && values[f.name] == null) values[f.name] = listOf(f.default)
    if (f.kind == "choice" && f.required && values[f.name] == null && f.options.isNotEmpty())
      values[f.name] = listOf(f.options.first())
    if (f.kind == "oneof" && values[f.name + ".type"] == null && f.cases.isNotEmpty())
      values[f.name + ".type"] = listOf(f.cases.first().first)
    if (f.kind == "date" && f.required && values[f.name] == null) values[f.name] = listOf(LocalDate.now().toString())
    defaults(f.fields, values)
    f.cases.forEach { defaults(it.second, values) }
  }
}

/** a field's name inside an item of a list: `#` its index */
private fun named(f: Field, prefix: Pair<String, Int>?): String =
  prefix?.let { (list, i) -> f.name.replace("$list.#", "$list.$i") } ?: f.name

@Composable
private fun Control(f: Field, prefix: Pair<String, Int>?, look: LookSpec, values: MutableMap<String, List<String>>,
  counts: MutableMap<String, Int>) {
  val name = named(f, prefix)
  val muted = look.tone("muted") ?: Color.Gray
  val accent = look.tone("accent") ?: Color(0xFF0969DA)
  fun text(): String = values[name]?.firstOrNull() ?: ""
  fun set(v: String) { if (v.isEmpty()) values.remove(name) else values[name] = listOf(v) }
  @Composable fun label(s: String) = Text(s, fontSize = (look.size * 0.8).sp, fontWeight = FontWeight.SemiBold,
    color = muted)
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    when (f.kind) {
      "bool" -> Row { Text(f.label, Modifier.weight(1f)); Switch(values[name]?.firstOrNull() == "true",
        { values[name] = if (it) listOf("true") else emptyList() }, Modifier.testTag("field-$name")) }
      "group" -> { label(f.label); f.fields.forEach { Control(it, prefix, look, values, counts) } }
      "oneof" -> {
        label(f.label)
        val chosen = values["$name.type"]?.firstOrNull()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          f.cases.forEach { (n, _) -> FilterChip(chosen == n, { values["$name.type"] = listOf(n) }, { Text(n) }) }
        }
        f.cases.firstOrNull { it.first == chosen }?.second?.forEach { Control(it, prefix, look, values, counts) }
      }
      "many" -> {
        label(f.label)
        val n = counts[name] ?: maxOf(f.min, 1)
        (0 until n).forEach { i -> Column(Modifier.padding(start = look.space.dp)) {
          f.item.forEach { Control(it, name to i, look, values, counts) } } }
        Row {
          if (f.max?.let { n < it } != false) TextButton({ counts[name] = n + 1 }) {
            Text(look.word("add", "Add"), color = accent) }
          if (n > f.min && n > 0) TextButton({ counts[name] = n - 1
            values.keys.filter { it.startsWith("$name.${n - 1}.") }.forEach { values.remove(it) } }) {
            Text(look.word("remove", "Remove"), color = accent) }
        }
      }
      "choice" -> {
        label(f.label)
        var expanded by remember { mutableStateOf(false) }
        Box {
          TextButton({ expanded = true }) { Text(text().ifEmpty { look.word("optional", "—") }, color = accent) }
          DropdownMenu(expanded, { expanded = false }) {
            if (!f.required) DropdownMenuItem({ Text(look.word("optional", "—")) }, { set(""); expanded = false })
            f.options.forEach { o -> DropdownMenuItem({ Text(o) }, { set(o); expanded = false }) }
          }
        }
      }
      "date" -> {
        label(f.label)
        val context = LocalContext.current
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(modifier = Modifier.testTag("add-$name"), onClick = {
            val d = text().let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
            if (!f.required && text().isEmpty()) set(LocalDate.now().toString())
            else DatePickerDialog(context, { _, y, m, day -> set(LocalDate.of(y, m + 1, day).toString()) },
              d.year, d.monthValue - 1, d.dayOfMonth).show()
          }) { Text(text().ifEmpty { look.word("add", "Add") }, color = accent) }
          if (!f.required && text().isNotEmpty()) TextButton({ set("") }) {
            Text(look.word("remove", "Remove"), color = accent) }
        }
      }
      else -> {
        label(f.label)
        val keyboard = when (f.kind) { "integer" -> KeyboardType.Number; "number" -> KeyboardType.Decimal
          else -> KeyboardType.Text }
        TextField(text(), { set(it) }, Modifier.fillMaxWidth().testTag("field-$name"),
          placeholder = { Text(f.label) }, singleLine = f.kind != "long" && f.kind != "lines",
          minLines = if (f.kind == "long" || f.kind == "lines") 3 else 1,
          visualTransformation = if (f.kind == "secret") PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
          keyboardOptions = KeyboardOptions(keyboardType = keyboard), shape = RoundedCornerShape(10.dp),
          colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedContainerColor = look.tone("surface") ?: Color(0xFFF6F8FA),
            unfocusedContainerColor = look.tone("surface") ?: Color(0xFFF6F8FA)))
      }
    }
    f.doc?.let { Text(it, fontSize = (look.size * 0.75).sp, color = muted) }
  }
}

@Suppress("unused")
private fun Modifier.filled(look: LookSpec) =
  background(look.tone("surface") ?: Color(0xFFF6F8FA), RoundedCornerShape(10.dp)).padding(12.dp)
