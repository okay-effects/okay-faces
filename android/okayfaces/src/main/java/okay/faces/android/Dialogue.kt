// THE APPLICATION'S DIALOGUE (specs/mobile.md): opened by its class name, a choice sent, back; a screen each time.
// Each call made on a thread of its own — a step may wait for the network — its screen published on the main one.
// The same stack as the iOS face's: a step one may go back from goes deeper, unless its page is already there.
package okay.faces.android

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okay.faces.mobile.Shell
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class Dialogue(app: String) {
  /** the steps shown, the last the current one: the ones before it those one may go back to */
  var stack by mutableStateOf(listOf(Screen.empty)); private set
  /** the look as the application gives it, both palettes: the face takes the one the phone is set to */
  var look by mutableStateOf(JSONObject()); private set
  /** a new step each time a choice is taken: the forms drawn afresh; a rejected one keeps what was typed */
  var turn by mutableIntStateOf(0); private set
  /** the library at work */
  var busy by mutableStateOf(true); private set
  val screen: Screen get() = stack.last()

  private val main = Handler(Looper.getMainLooper())
  private var opened: Shell.Opened? = null

  private enum class Move { FORWARD, REPLACE, REFRESH }

  init {
    call(Move.REPLACE) {
      val o = Shell.open(app)
      opened = o
      o.onMoved { main.post { refresh() } }
      val s = o.screen()
      look = try { JSONObject(o.look()) } catch (e: Exception) { JSONObject() }
      s
    }
  }

  /** the action taken with the fields typed, by their dotted names */
  fun act(action: String, form: Map<String, List<String>> = emptyMap()) {
    val f = JSONObject()
    form.forEach { (k, v) -> f.put(k, JSONArray(v)) }
    val choice = JSONObject().put("action", action).put("form", f).toString()
    call(Move.FORWARD) { opened?.act(choice) ?: "{}" }
  }

  /** the step before: popped at once (the system's back already took it away), then asked of the library */
  fun back() {
    if (stack.size > 1) stack = stack.dropLast(1)
    call(Move.REPLACE) { opened?.back() ?: "{}" }
  }

  fun refresh() = call(Move.REFRESH) { opened?.screen() ?: "{}" }

  private fun call(move: Move, work: () -> String) {
    busy = true
    scala.execute {
      val text = try { work() } catch (e: Throwable) { JSONObject().put("error", e.toString()).toString() }
      main.post {
        val s = Screen.of(text)
        when {
          move == Move.FORWARD && s.problems.isNotEmpty() -> stack = stack.dropLast(1) + s
          move == Move.FORWARD -> {
            turn += 1
            val i = s.title?.let { t -> stack.indexOfLast { it.title == t } } ?: -1
            stack = when {
              !s.back -> listOf(s)
              i >= 0 -> stack.take(i) + s
              else -> stack + s
            }
          }
          move == Move.REPLACE -> { turn += 1; stack = stack.dropLast(1) + s }
          else -> stack = stack.dropLast(1) + s
        }
        busy = false
      }
    }
  }

  companion object {
    /** the library's thread: the application's Scala called on one thread alone, in turn */
    private val scala = Executors.newSingleThreadExecutor { r -> Thread(r, "okay-faces") }
  }
}
