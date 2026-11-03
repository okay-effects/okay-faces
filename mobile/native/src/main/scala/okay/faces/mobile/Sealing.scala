package okay.faces.mobile

/** SEALING IN `Device.Memory` on Scala Native: none here (no JCA) — a phone seals with its own (CryptoKit, through the
 * shell); the JVM's `Sealing` seals in tests */
object Sealing {
  def seal(key: String, aad: String, plain: String): String = ""
  def unseal(key: String, aad: String, box: String): Option[String] = None
}
