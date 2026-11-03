package okay.faces.mobile

import okay.effects.json.Json

/** the phone's socket, sealing and scanning, as `Device.Memory` answers them: what comes on a socket a device event;
 * a sealed text opened by its key only, framed as okay-serverless' Sealer frames it */
class DevicePowersSuite extends munit.FunSuite {
  private def str(j: Json, k: String): Option[String] = j match {
    case o: Json.Obj => o.get(k).collect { case Json.Str(s) => s }; case _ => None
  }

  test("a socket: opened, its texts sent, what comes back an event, its closing an event") {
    var peer: String => Unit = _ => ()
    val sent = scala.collection.mutable.ListBuffer.empty[String]
    val d = new Device.Memory(sockets = (url, incoming) =>
      if (url.startsWith("wss://relay/")) { peer = incoming; Some(t => sent += t) } else None)
    val events = scala.collection.mutable.ListBuffer.empty[Json]
    val stop = d.events.listen(events += _)
    val Right(id) = Device.answer(Device.Socket[Either[String, String]]("wss://relay/r/x/guest", x => x),
      d.call(Device.request(Device.Socket[Either[String, String]]("wss://relay/r/x/guest", x => x))))
    assert(Device.answer(Device.SocketSend[Boolean](id, "hello", b => b),
      d.call(Device.request(Device.SocketSend[Boolean](id, "hello", b => b)))))
    assertEquals(sent.toList, List("hello"))
    peer("online")
    assertEquals(events.toList.map(str(_, "text")), List(Some("online")))
    assertEquals(str(events.head, "id"), Some(id))
    d.call(Device.request(Device.SocketClose[Unit](id, u => u)))
    assertEquals(events.toList.lastOption.flatMap(str(_, "event")), Some("closed"))
    stop()
    peer("late")
    assertEquals(events.size, 2)
    // a socket that does not open: why
    assert(Device.answer(Device.Socket[Either[String, String]]("wss://elsewhere", x => x),
      d.call(Device.request(Device.Socket[Either[String, String]]("wss://elsewhere", x => x)))).isLeft)
  }

  test("sealing: opened by the same key and data only; framed iv ‖ ciphertext ‖ tag") {
    val key = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(Array.fill[Byte](32)(7))
    val other = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(Array.fill[Byte](32)(8))
    val box = Sealing.seal(key, "okay-remote/1", """{"k":"hello"}""")
    assertEquals(java.util.Base64.getUrlDecoder.decode(box).length, 12 + 13 + 16)
    assertEquals(Sealing.unseal(key, "okay-remote/1", box), Some("""{"k":"hello"}"""))
    assertEquals(Sealing.unseal(other, "okay-remote/1", box), None)
    assertEquals(Sealing.unseal(key, "another/1", box), None)
    assertEquals(Sealing.unseal(key, "okay-remote/1", box.dropRight(2) + "AA"), None)
    assertNotEquals(Sealing.seal(key, "okay-remote/1", "x"), Sealing.seal(key, "okay-remote/1", "x"))
  }

  test("a scan: the text, or none") {
    assertEquals(Device.answer(Device.Scan[Option[String]](x => x),
      new Device.Memory(scanned = Some("https://r/#s=abc")).call(Json.obj("op" -> Json.Str("scan")))),
      Some("https://r/#s=abc"))
    assertEquals(Device.answer(Device.Scan[Option[String]](x => x), Device.memory().call(Json.obj("op" ->
      Json.Str("scan")))), None)
  }
}
