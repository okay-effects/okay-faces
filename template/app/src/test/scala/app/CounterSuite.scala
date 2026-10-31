package app

import okay.faces.mobile.{Device, MobileApp}

class CounterSuite extends munit.FunSuite {
  test("one more, kept on the phone; the next run starts there") {
    val phone = Device.memory()
    MobileApp.phone = phone
    val d = new CounterApp().dialogue
    assert(d.open().contains("0 so far"))
    assert(d.act("""{"action": "more"}""").contains("1 so far"))
    assertEquals(phone.kept.get("count"), Some("1"))
    assert(new CounterApp().dialogue.open().contains("1 so far"))
  }
}
