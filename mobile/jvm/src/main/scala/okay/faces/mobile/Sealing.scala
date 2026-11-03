package okay.faces.mobile

/** AES-256-GCM as okay-serverless' Sealer frames it (`iv ‖ ciphertext ‖ tag`, base64url), for `Device.Memory` and any
 * host with JCA; a phone seals with its own (CryptoKit) */
object Sealing {
  private def b64(b: Array[Byte]): String = java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(b)
  private def unb64(s: String): Option[Array[Byte]] =
    try Some(java.util.Base64.getUrlDecoder.decode(s)) catch { case _: IllegalArgumentException => None }
  private def cipher(mode: Int, key: Array[Byte], iv: Array[Byte], aad: String) = {
    val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
    c.init(mode, new javax.crypto.spec.SecretKeySpec(key, "AES"), new javax.crypto.spec.GCMParameterSpec(128, iv))
    c.updateAAD(aad.getBytes("UTF-8"))
    c
  }
  def seal(key: String, aad: String, plain: String): String = {
    val iv = new Array[Byte](12)
    new java.security.SecureRandom().nextBytes(iv)
    val box = cipher(javax.crypto.Cipher.ENCRYPT_MODE, unb64(key).getOrElse(Array.emptyByteArray), iv, aad)
      .doFinal(plain.getBytes("UTF-8"))
    b64(iv ++ box)
  }
  def unseal(key: String, aad: String, box: String): Option[String] =
    for {
      k <- unb64(key)
      b <- unb64(box) if b.length > 28
      plain <- try Some(cipher(javax.crypto.Cipher.DECRYPT_MODE, k, b.take(12), aad).doFinal(b.drop(12)))
        catch { case _: java.security.GeneralSecurityException => None }
    } yield new String(plain, "UTF-8")
}
