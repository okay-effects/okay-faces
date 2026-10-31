// THE FACE: the screen drawn — what was told, the step's view, the problems, the actions — in the application's look.
// `OkayFacesView(app = "my.App")` is the whole of an app's Compose; `DeviceBridge.install(this)` gives it the phone.
package okay.faces.android

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OkayFacesView(app: String) {
  val dialogue = remember(app) { Dialogue(app) }
  // the look as the phone is set: its palette at night when dark; its sizes in sp, which the font size scales
  val look = LookSpec(dialogue.look, isSystemInDarkTheme())
  val accent = look.tone("accent") ?: Color(0xFF0969DA)
  val scheme = (if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()).copy(
    primary = accent, onPrimary = look.tone("bg") ?: Color.White, background = look.page, surface = look.page,
    onSurface = look.tone("fg") ?: Color.Black, secondaryContainer = accent.copy(alpha = 0.18f),
    onSecondaryContainer = accent, surfaceContainerHighest = look.tone("surface") ?: Color(0xFFF6F8FA))
  MaterialTheme(colorScheme = scheme) {
    // the system's back — the gesture from the edge, the button — takes the step back when there is one behind
    BackHandler(enabled = dialogue.stack.size > 1) { dialogue.back() }
    Scaffold(
      containerColor = look.page,
      topBar = {
        TopAppBar(
          title = {},
          navigationIcon = {
            if (dialogue.stack.size > 1) IconButton(onClick = { dialogue.back() }, Modifier.testTag("back")) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, look.word("back", "Back"), tint = accent)
            }
          },
          actions = {
            if (dialogue.busy) CircularProgressIndicator(Modifier.size(20.dp).testTag("busy"), strokeWidth = 2.dp)
            Spacer(Modifier.width(16.dp))
          },
          colors = TopAppBarDefaults.topAppBarColors(containerColor = look.page))
      }) { inner ->
      // deeper slides in from the end, back from the start, as a stack of pages goes
      AnimatedContent(targetState = dialogue.stack.size, label = "depth", transitionSpec = {
        if (targetState > initialState) slideInHorizontally { it } togetherWith slideOutHorizontally { -it / 3 }
        else slideInHorizontally { -it / 3 } togetherWith slideOutHorizontally { it }
      }) { depth ->
        Page(dialogue, look, depth - 1, Modifier.padding(inner))
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(dialogue: Dialogue, look: LookSpec, index: Int, modifier: Modifier) {
  val screen = dialogue.stack.getOrNull(index) ?: dialogue.screen
  val top = index == dialogue.stack.size - 1
  val scroll = rememberScrollState()
  // a new step is read from its start
  LaunchedEffect(dialogue.turn) { scroll.animateScrollTo(0) }
  // pulled down: the screen asked again — what an event brought, if anything
  PullToRefreshBox(isRefreshing = false, onRefresh = { dialogue.refresh() }, modifier = modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = (look.space * 2).dp,
      vertical = look.space.dp), verticalArrangement = Arrangement.spacedBy((look.space * 2).dp)) {
      screen.error?.let { Dressed(look, "notice", "notice-problem") { Text(it) } }
      screen.told.forEach { v ->
        Card(look) { CompositionLocalProvider(LocalInherited provides Inherited(color = "muted")) {
          View(v, look, dialogue, false) } }
      }
      Card(look) { View(screen.view, look, dialogue, screen.refs && top) }
      if (screen.problems.isNotEmpty()) Dressed(look, "problems") {
        Column { screen.problems.forEach { Text(it) } }
      }
      key(dialogue.turn) { Actions(screen.actions.filter { it.name != "ref" }, look, dialogue, top) }
    }
  }
}

/** A CARD, as a phone groups what belongs together: the look's ground, rounded, on the page */
@Composable
fun Card(look: LookSpec, content: @Composable () -> Unit) {
  Box(Modifier.fillMaxWidth().background(look.tone("bg") ?: Color.White, RoundedCornerShape(16.dp))
    .padding((look.space * 2).dp)) { content() }
}

/** A VIEW, drawn: each kind its Compose, each part dressed by its role */
@Composable
fun View(node: Node, look: LookSpec, dialogue: Dialogue, refs: Boolean) {
  val haptic = LocalHapticFeedback.current
  when (node) {
    is Node.Empty -> {}
    is Node.Text -> Dressed(look, "text") { Text(node.s) }
    is Node.Heading -> Dressed(look, "heading", "heading-${node.level.coerceIn(1, 3)}") {
      Text(node.s, Modifier.semantics { heading() }) }
    is Node.Notice -> Dressed(look, "notice", "notice-${node.level}", modifier = Modifier.fillMaxWidth()) {
      Text(node.s) }
    is Node.Fields -> Dressed(look, "fields") {
      Column(verticalArrangement = Arrangement.spacedBy(look.gap("fields"))) {
        node.rows.forEach { (k, v) ->
          Row(horizontalArrangement = Arrangement.spacedBy(look.space.dp)) {
            Box(Modifier.widthIn(min = 72.dp)) { Dressed(look, "label") { Text(k) } }
            View(v, look, dialogue, refs)
          }
        }
      }
    }
    is Node.Table -> Dressed(look, "table") {
      Column(verticalArrangement = Arrangement.spacedBy(look.space.dp)) {
        if (node.columns.any { it.isNotEmpty() }) Row { node.columns.forEach { c ->
          Box(Modifier.weight(1f)) { Dressed(look, "label") { Text(c) } } } }
        node.rows.forEach { r ->
          Row(horizontalArrangement = Arrangement.spacedBy(look.space.dp)) {
            r.forEach { c -> Box(Modifier.weight(1f).aspectRatio(1f)) { View(c, look, dialogue, refs) } }
          }
        }
      }
    }
    is Node.Items -> Column {
      // rows, as a phone lists them: a line between, the whole row the place to tap
      node.items.forEachIndexed { i, x ->
        if (i > 0) HorizontalDivider(color = look.tone("border") ?: Color.LightGray)
        Box(Modifier.fillMaxWidth().padding(vertical = (look.space * 1.25).dp)) { View(x, look, dialogue, refs) }
      }
    }
    is Node.Group -> Dressed(look, "group") {
      Column(verticalArrangement = Arrangement.spacedBy(look.gap("group"))) {
        node.title?.let { Dressed(look, "group-title") { Text(it) } }
        if (look.rule("group").row) Row(horizontalArrangement = Arrangement.spacedBy(look.gap("group"))) {
          node.children.forEach { View(it, look, dialogue, refs) } }
        else node.children.forEach { View(it, look, dialogue, refs) }
      }
    }
    is Node.Ref -> Dressed(look, "ref") {
      val centered = LocalInherited.current.centered
      Box(Modifier.fillMaxSize().testTag("ref-${node.key}").let { m ->
        if (refs) m.clickable { haptic.performHapticFeedback(HapticFeedbackType.LongPress)
          dialogue.act("ref", mapOf("key" to listOf(node.key))) } else m },
        contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart) { Text(node.label) }
    }
    is Node.Link -> Dressed(look, "link") {
      val uri = LocalUriHandler.current
      Text(node.label, Modifier.clickable { uri.openUri(node.url) }, textDecoration = TextDecoration.Underline)
    }
    is Node.Datum -> Dressed(look, "datum") { Text(node.text) }
    is Node.Masked -> Dressed(look, "masked") { Text(node.hint) }
    is Node.Image -> Dressed(look, "image") { ImageView(node) }
    is Node.Marked -> Dressed(look, "mark", "mark-${node.mark}", modifier = Modifier.fillMaxSize()) {
      View(node.view, look, dialogue, refs) }
    is Node.Picture -> Dressed(look, "picture") { Picture(node, look) }
  }
}

/** A PICTURE: boxes, bands and labels on a canvas of the program's size, scaled to the width there is */
@Composable
private fun Picture(p: Node.Picture, look: LookSpec) {
  val measurer = rememberTextMeasurer()
  val fg = look.tone("fg") ?: Color.Black
  val ratio = (p.width / p.height).toFloat()
  Canvas(Modifier.fillMaxWidth().aspectRatio(ratio).semantics { contentDescription = p.text }) {
    val k = minOf(size.width / p.width.toFloat(), size.height / p.height.toFloat())
    p.shapes.forEach { s ->
      fun n(key: String) = (s.num(key) ?: 0.0).toFloat() * k
      when (s.str("shape")) {
        "box" -> drawRect(look.markColor(s.str("mark") ?: ""), Offset(n("x"), n("y")), Size(n("w"), n("h")))
        "band" -> {
          val h = n("thickness") / 2; val mx = (n("x0") + n("x1")) / 2
          val path = Path().apply {
            moveTo(n("x0"), n("y0") - h)
            cubicTo(mx, n("y0") - h, mx, n("y1") - h, n("x1"), n("y1") - h)
            lineTo(n("x1"), n("y1") + h)
            cubicTo(mx, n("y1") + h, mx, n("y0") + h, n("x0"), n("y0") + h)
            close()
          }
          drawPath(path, look.markColor(s.str("mark") ?: "").copy(alpha = 0.5f))
        }
        "label" -> {
          val t = measurer.measure(s.str("text") ?: "", TextStyle(color = fg, fontSize = (look.size * 0.8).sp))
          val dx = when (s.str("anchor")) { "middle" -> t.size.width / 2f; "end" -> t.size.width.toFloat(); else -> 0f }
          drawText(t, topLeft = Offset(n("x") - dx, n("y") - t.size.height))
        }
        else -> {}
      }
    }
  }
}

/** AN IMAGE: decoded at once when the source is a data: URI (a photo the phone took), else fetched from its address on
 * a thread of its own; its words to TalkBack */
@Composable
private fun ImageView(node: Node.Image) {
  val bitmap by produceState<android.graphics.Bitmap?>(null, node.source) {
    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      runCatching {
        val bytes = if (node.source.startsWith("data:"))
          android.util.Base64.decode(node.source.substringAfter(','), android.util.Base64.DEFAULT)
        else java.net.URL(node.source).openStream().use { it.readBytes() }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
      }.getOrNull()
    }
  }
  val b = bitmap
  if (b == null) Text("[${node.alt}]")
  else androidx.compose.foundation.Image(b.asImageBitmap(), node.alt,
    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).testTag("image"),
    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth)
}
