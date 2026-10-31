package okay.faces.mobile.example

import okay.effects.json.Json

class AppsSuite extends munit.FunSuite {
  def read(s: String): Json = okay.effects.json.Descent.parse(s).fold(e => fail(e.message), j => j)
  def at(j: Json, k: String): Json = j match { case o: Json.Obj => o.get(k).getOrElse(Json.Null); case _ => Json.Null }

  test("the list on the shell's wire: an item added by the form, the list shown with it") {
    val d = new TodoApp().dialogue
    val first = read(d.open())
    assertEquals(at(first, "title"), Json.Str("To do"))
    val after = read(d.act("""{"action": "add", "form": {"title": "milk"}}"""))
    assert(Json.print(after).contains("added: milk"), Json.print(after))
    assert(Json.print(after).contains("\"ref\""), Json.print(after))
  }
}

class NotesSuite extends munit.FunSuite {
  import okay.faces.mobile.{Device, MobileApp}
  def read(s: String): Json = okay.effects.json.Descent.parse(s).fold(e => fail(e.message), j => j)

  test("notes on a phone in memory: kept between runs, GitHub's answer shown, a reminder posted") {
    val phone = new Device.Memory(network = (_, u, _) =>
      Device.Reply(200, Map.empty, """{"description": "faces", "stargazers_count": 3, "pushed_at": "today"}"""))
    MobileApp.phone = phone
    val d = new NotesApp().dialogue
    assert(d.open().contains("Nothing kept yet."))
    d.act("""{"action": "add", "form": {"text": "Buy milk"}}""")
    assertEquals(phone.kept.get("notes"), Some("Buy milk"))
    assert(new NotesApp().dialogue.open().contains("Buy milk"), "kept for the next run")
    assert(d.act("""{"action": "github"}""").contains("\"Stars\""))
    assert(d.act("""{"action": "remind"}""").contains("A reminder in 5 seconds."))
    assertEquals(phone.notified.toList, List("Notes" -> "Buy milk"))
  }
}

class NotesDeviceSuite extends munit.FunSuite {
  import okay.faces.mobile.{Device, MobileApp}

  test("the phone's other powers: where it is, a photo to a note, a note shared, the person checked") {
    val phone = new Device.Memory(photo = Some("data:image/jpeg;base64,/9j/4A"),
      place = Some(Device.Place(51.1079, 17.0385, 12)), person = false)
    MobileApp.phone = phone
    val d = new NotesApp().dialogue
    d.open()
    val here = d.act("""{"action": "here"}""")
    assert(here.contains("At 51.1079, 17.0385"), here)
    assert(d.act("""{"action": "ref", "form": {"key": "1"}}""").contains("Add a photo"))
    val withPhoto = d.act("""{"action": "photo"}""")
    assert(withPhoto.contains("\"type\":\"image\"") && withPhoto.contains("/9j/4A"), withPhoto)
    d.act("""{"action": "share-note"}""")
    assertEquals(phone.shared.toList, List("At 51.1079, 17.0385"))
    d.back()
    assert(d.act("""{"action": "unlock"}""").contains("Not unlocked."))
  }
}

/** TWO PHONES, ONE PERSON, against a real backend — okay-serverless' accounts on `wrangler dev` with ACCOUNTS_TEST
 * (`OKAY_ACCOUNTS`, default http://127.0.0.1:8790; the suite is skipped when it does not answer): a note added on
 * one phone is on the other once the same person signs in there */
class SyncSuite extends munit.FunSuite {
  import okay.faces.mobile.{Device, MobileApp, MobileDialogue}
  val server = sys.env.getOrElse("OKAY_ACCOUNTS", "http://127.0.0.1:8790")
  def up: Boolean = scala.util.Try { val c = new java.net.URI(server).toURL.openConnection()
    c.setConnectTimeout(1000); c.connect(); true }.getOrElse(false)

  /** a phone in memory whose network is the real one, the program's headers sent on */
  def phone(): Device.Memory = {
    lazy val p: Device.Memory = new Device.Memory(network = (m, u, b) => {
      val c = new java.net.URI(u).toURL.openConnection().asInstanceOf[java.net.HttpURLConnection]
      c.setRequestMethod(m)
      p.asking.foreach { case (k, v) => c.setRequestProperty(k, v) }
      if (b.nonEmpty) { c.setDoOutput(true); c.getOutputStream.write(b.getBytes("UTF-8")) }
      val status = c.getResponseCode
      val in = if (status >= 400) c.getErrorStream else c.getInputStream
      Device.Reply(status, Map.empty, if (in == null) "" else new String(in.readAllBytes(), "UTF-8"))
    })
    p
  }

  test("a note added on one phone is on the other when the same person signs in") {
    assume(up, s"no accounts server at $server")
    val email = s"t${System.nanoTime}@example.org"
    def open(p: Device.Memory): MobileDialogue = {
      MobileApp.phone = p; p.kept("cloud") = server; new NotesApp().dialogue
    }
    def signIn(d: MobileDialogue): String = {
      d.act("""{"action": "sync"}""")
      val s = d.act(s"""{"action": "email", "form": {"email": "$email"}}""")
      val code = "A test server's code: (\\d{6})".r.findFirstMatchIn(s).map(_.group(1)).getOrElse(fail(s))
      d.act(s"""{"action": "code", "form": {"code": "$code"}}""")
    }
    val a = open(phone())
    a.open()
    a.act("""{"action": "add", "form": {"text": "From the first phone"}}""")
    assert(signIn(a).contains("shared as"))
    val b = open(phone())
    assert(b.open().contains("Nothing kept yet."))
    val signed = signIn(b)
    assert(signed.contains("From the first phone") && signed.contains("shared as"), signed)
  }
}
