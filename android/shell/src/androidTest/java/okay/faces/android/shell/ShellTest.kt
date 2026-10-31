// THE SHELL DRIVEN as a person would, on the emulator: the examples' screens tapped and typed, each step's screen
// kept (pulled by android/test.sh) — the Android face checked end to end, the program running as Scala on ART.
package okay.faces.android.shell

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShellTest {
  @get:Rule val rule = createEmptyComposeRule()
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val device = UiDevice.getInstance(instrumentation)
  private var scenario: ActivityScenario<MainActivity>? = null

  private fun launch(app: String, reset: Boolean = false) {
    scenario?.close()
    val i = Intent(instrumentation.targetContext, MainActivity::class.java)
      .putExtra("app", app).putExtra("reset", reset)
    scenario = ActivityScenario.launch(i)
  }
  @After fun close() {
    scenario?.close()
    device.executeShellCommand("cmd uimode night no"); device.executeShellCommand("settings put system font_scale 1.0")
  }

  /** whether the app's screen has it now — none while a window of the system covers the app */
  private fun has(find: () -> List<*>): Boolean = try { find().isNotEmpty() } catch (e: IllegalStateException) { false }
  private fun waitText(s: String, ms: Long = 10000) =
    rule.waitUntil(ms) { has { rule.onAllNodesWithText(s, substring = true).fetchSemanticsNodes() } }
  private fun waitTag(t: String, ms: Long = 10000) =
    rule.waitUntil(ms) { has { rule.onAllNodesWithTag(t).fetchSemanticsNodes() } }
  private fun type(tag: String, text: String) { rule.onNodeWithTag(tag).performTextInput(text) }
  private fun tap(tag: String) { rule.onNodeWithTag(tag).performScrollTo().performClick() }

  /** the screen as it is, kept where the app's removal does not take it: /sdcard/Download/okay-faces */
  private fun shot(name: String, idle: Boolean = true) {
    if (idle) rule.waitForIdle()
    Thread.sleep(600)
    device.executeShellCommand("mkdir -p /sdcard/Download/okay-faces")
    device.executeShellCommand("screencap -p /sdcard/Download/okay-faces/$name.png")
  }

  @Test fun theList() {
    launch("okay.faces.mobile.example.TodoApp", reset = true)
    waitText("Nothing to do.")
    shot("todo-1-empty")
    type("field-title", "Milk"); tap("act-add")
    waitText("added: Milk")
    type("field-title", "Bread"); tap("add-due"); tap("act-add")
    waitTag("ref-2")
    shot("todo-2-two-items")
    rule.onNodeWithTag("ref-1").performClick()
    waitText("1 of 2 done")
    shot("todo-3-one-done")
    // Android may end the app: the list is taken up where it was
    launch("okay.faces.mobile.example.TodoApp")
    waitTag("ref-2"); waitText("1 of 2 done")
    // at night, and read large: the look's dark palette, the phone's font size
    device.executeShellCommand("cmd uimode night yes"); Thread.sleep(1500); waitTag("ref-2")
    shot("todo-4-dark")
    device.executeShellCommand("cmd uimode night no")
    device.executeShellCommand("settings put system font_scale 1.5"); Thread.sleep(1500); waitTag("ref-2")
    shot("todo-5-large")
  }

  @Test fun noughts() {
    launch("okay.faces.mobile.example.NoughtsApp")
    waitTag("ref-5")
    shot("noughts-1-board")
    rule.onNodeWithTag("ref-5").performClick()
    rule.waitUntil(5000) { rule.onAllNodesWithTag("ref-5").fetchSemanticsNodes().isEmpty() }
    shot("noughts-2-moved")
  }

  /** the device: a note kept between runs, GitHub's page fetched, a reminder notified */
  @Test fun notesOnTheDevice() {
    launch("okay.faces.mobile.example.NotesApp", reset = true)
    waitText("Nothing kept yet.")
    type("field-text", "Buy milk"); tap("act-add")
    waitText("1 kept on this phone")
    launch("okay.faces.mobile.example.NotesApp")
    waitTag("ref-1")
    shot("notes-1-kept")
    tap("act-github")
    waitText("Stars", 30000)
    shot("notes-2-github")
    tap("act-remind")
    device.wait(Until.findObject(By.text("Allow")), 10000)?.click()
    waitText("A reminder in 5 seconds.")
    device.openNotification()
    // the notification itself, in the system's shade — not the note on the app's screen beneath it
    check(device.wait(Until.hasObject(By.pkg("com.android.systemui").textContains("Buy milk")), 20000)) {
      "the reminder notified" }
    shot("notes-3-reminded", idle = false)
    device.pressBack()
  }

  /** a note's page, deeper; back by the system's back, as Android goes back; deleted from there, the list again */
  @Test fun notePageAndBack() {
    launch("okay.faces.mobile.example.NotesApp", reset = true)
    waitText("Nothing kept yet.")
    type("field-text", "Call mum"); tap("act-add")
    waitTag("ref-1")
    rule.onNodeWithTag("ref-1").performClick()
    waitTag("act-delete")
    shot("notes-4-page")
    Espresso.pressBack()
    waitText("1 kept on this phone")
    check(rule.onAllNodesWithTag("act-delete").fetchSemanticsNodes().isEmpty())
    rule.onNodeWithTag("ref-1").performClick()
    waitTag("act-delete"); tap("act-delete")
    waitText("Nothing kept yet.")
  }

  /** the phone's other powers: where it is (the emulator's place, set from outside), a photo picked onto a note, the
   * note shared, the person checked by the screen lock's PIN the test sets */
  @Test fun notesPowers() {
    val pkg = instrumentation.targetContext.packageName
    instrumentation.uiAutomation.grantRuntimePermission(pkg, android.Manifest.permission.ACCESS_FINE_LOCATION)
    device.executeShellCommand("locksettings set-pin 1111")
    launch("okay.faces.mobile.example.NotesApp", reset = true)
    waitText("Nothing kept yet.")
    tap("act-here")
    waitText("At 51.1079, 17.0385", 30000)

    rule.onNodeWithTag("ref-1").performClick()
    waitTag("act-photo"); tap("act-photo")
    // the system's photo picker: the first photo in it
    val photo = device.wait(Until.findObject(By.descContains("Photo")), 15000)
    checkNotNull(photo) { "the picker's photos" }.click()
    waitTag("image", 20000)
    shot("notes-5-photo")

    tap("act-share-note")
    // the system's sheet: the intent resolver's (Android 14 and on), or the system's own before
    check(device.wait(Until.hasObject(By.pkg(java.util.regex.Pattern.compile("com.android.intentresolver|android"))),
      10000)) { "the share sheet" }
    shot("notes-6-share", idle = false)
    device.pressBack()
    waitTag("act-delete")

    Espresso.pressBack()
    waitTag("act-unlock"); tap("act-unlock")
    // the system's check: the PIN of the screen lock
    check(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 10000)) { "the system asks for the PIN" }
    shot("notes-7-unlock", idle = false)
    device.executeShellCommand("input text 1111"); device.executeShellCommand("input keyevent 66")
    waitText("It is you.", 15000)
    device.executeShellCommand("locksettings clear --old 1111")
  }
}
