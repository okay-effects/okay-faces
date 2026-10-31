// THE PHONE, AS THE PROGRAM ASKS IT (specs/mobile.md, "The device"): each operation of the Scala `Device` effect
// answered by Android — HttpURLConnection, SharedPreferences, the Keystore, notifications, an Intent to view. Called
// on the library's own thread, never the main one: an operation may wait for the network, or for the person.
package okay.faces.android

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import okay.faces.mobile.Shell
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class DeviceBridge private constructor(private val activity: ComponentActivity) : Shell.Phone {
  private val context: Context = activity.applicationContext
  private val prefs = context.getSharedPreferences("okay-faces", Context.MODE_PRIVATE)
  private val secrets = context.getSharedPreferences("okay-faces-secrets", Context.MODE_PRIVATE)
  private val main = Handler(Looper.getMainLooper())
  @Volatile private var asked: ((Boolean) -> Unit)? = null
  @Volatile private var picked: ((android.net.Uri?) -> Unit)? = null
  @Volatile private var snapped: ((android.graphics.Bitmap?) -> Unit)? = null
  // registered while the activity is created, as Android requires
  private val permission: ActivityResultLauncher<String> =
    activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> asked?.invoke(ok) }
  private val picker = activity.registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked?.invoke(it) }
  private val camera = activity.registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { snapped?.invoke(it) }

  /** a permission, asked once of the person, waited for here */
  private fun allowed(p: String): Boolean {
    if (context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) return true
    return await { done -> asked = done; main.post { permission.launch(p) } } ?: false
  }
  /** what the main thread hands to `done`, waited for on this one */
  private fun <T> await(start: (((T) -> Unit)) -> Unit): T? {
    val done = CountDownLatch(1)
    var result: T? = null
    start { result = it; done.countDown() }
    done.await(120, TimeUnit.SECONDS)
    return result
  }

  override fun call(request: String): String {
    val op = try { JSONObject(request) } catch (e: Exception) { JSONObject() }
    val answer = try { answer(op) } catch (e: Throwable) { JSONObject().put("error", e.toString()) }
    return answer.toString()
  }

  private fun answer(op: JSONObject): JSONObject = when (op.str("op")) {
    "fetch" -> fetch(op)
    "load" -> value(prefs.getString(key(op), null))
    "save" -> { keep(prefs, key(op), op.str("value")); JSONObject() }
    "secret" -> value(secrets.getString(key(op), null)?.let { decrypt(it) })
    "hide" -> { keep(secrets, key(op), op.str("value")?.let { encrypt(it) }); JSONObject() }
    "now" -> JSONObject().put("value", System.currentTimeMillis())
    "notify" -> JSONObject().put("ok", notify(op.str("title") ?: "", op.str("body") ?: "", op.num("after") ?: 0.0))
    "open" -> JSONObject().put("ok", open(op.str("url") ?: ""))
    "photo" -> photo(op.str("from") ?: "library", (op.num("size") ?: 1024.0).toInt())?.let {
      JSONObject().put("value", it) } ?: JSONObject()
    "locate" -> locate()
    "share" -> JSONObject().put("ok", share(op.str("text") ?: "", op.str("url")))
    "verify" -> JSONObject().put("ok", verify(op.str("reason") ?: ""))
    else -> JSONObject().put("error", "no operation ${op.str("op")}")
  }

  private fun key(op: JSONObject) = "okay-faces." + (op.str("key") ?: "")
  /** a value kept under a key, or forgotten (`null`) */
  private fun keep(p: android.content.SharedPreferences, k: String, v: String?) {
    p.edit().apply { if (v == null) remove(k) else putString(k, v) }.commit()
  }
  private fun value(v: String?) = if (v == null) JSONObject() else JSONObject().put("value", v)

  /** the network, waited for: a reply, or status 0 and the reason */
  private fun fetch(op: JSONObject): JSONObject = try {
    val c = URL(op.str("url")).openConnection() as HttpURLConnection
    c.requestMethod = op.str("method") ?: "GET"
    c.connectTimeout = 30000; c.readTimeout = 30000
    op.optJSONObject("headers")?.let { h -> h.keys().forEach { c.setRequestProperty(it, h.optString(it)) } }
    val body = op.str("body") ?: ""
    if (body.isNotEmpty()) { c.doOutput = true; c.outputStream.use { it.write(body.toByteArray()) } }
    val status = c.responseCode
    val stream = if (status >= 400) c.errorStream else c.inputStream
    val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
    val headers = JSONObject()
    c.headerFields.forEach { (k, v) -> if (k != null) headers.put(k, v.joinToString(", ")) }
    JSONObject().put("status", status).put("headers", headers).put("body", text)
  } catch (e: Exception) {
    JSONObject().put("status", 0).put("error", e.toString())
  }

  /** a notification, asked for once, posted now or after some seconds */
  private fun notify(title: String, body: String, after: Double): Boolean {
    if (Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
      val done = CountDownLatch(1)
      var ok = false
      asked = { ok = it; done.countDown() }
      main.post { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
      done.await(60, TimeUnit.SECONDS)
      if (!ok) return false
    }
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel("okay-faces", "okay-faces", NotificationManager.IMPORTANCE_HIGH))
    val n = android.app.Notification.Builder(context, "okay-faces").setContentTitle(title).setContentText(body)
      .setSmallIcon(android.R.drawable.ic_dialog_info).setAutoCancel(true).build()
    main.postDelayed({ manager.notify((System.nanoTime() % Int.MAX_VALUE).toInt(), n) }, (after * 1000).toLong())
    return true
  }

  /** A PHOTO: from the camera, or picked; a JPEG at most `size` across, as a data: URI */
  private fun photo(from: String, size: Int): String? {
    val bitmap: android.graphics.Bitmap? = if (from == "camera") {
      if (!allowed(Manifest.permission.CAMERA)) null
      else await<android.graphics.Bitmap?> { done -> snapped = done; main.post { camera.launch(null) } }
    } else {
      val uri = await<android.net.Uri?> { done -> picked = done
        main.post { picker.launch(androidx.activity.result.PickVisualMediaRequest(
          ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
      uri?.let { u -> context.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it) } }
    }
    val b = bitmap ?: return null
    val k = minOf(1f, size.toFloat() / maxOf(b.width, b.height))
    val scaled = android.graphics.Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1),
      (b.height * k).toInt().coerceAtLeast(1), true)
    val out = java.io.ByteArrayOutputStream()
    scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
    return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
  }

  /** WHERE THE PHONE IS: the permission asked once, then the current fix */
  private fun locate(): JSONObject {
    if (!allowed(Manifest.permission.ACCESS_FINE_LOCATION)) return JSONObject()
    val manager = context.getSystemService(android.location.LocationManager::class.java)
    val provider = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
      .firstOrNull { manager.isProviderEnabled(it) } ?: return JSONObject()
    @Suppress("MissingPermission")
    val place = await<android.location.Location?> { done ->
      manager.getCurrentLocation(provider, null, context.mainExecutor) { done(it) } }
      ?: @Suppress("MissingPermission") manager.getLastKnownLocation(provider) ?: return JSONObject()
    return JSONObject().put("latitude", place.latitude).put("longitude", place.longitude)
      .put("accuracy", place.accuracy.toDouble())
  }

  /** SHARING: the phone's own sheet, its text and link */
  private fun share(text: String, url: String?): Boolean {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
      .putExtra(Intent.EXTRA_TEXT, if (url == null) text else "$text $url")
    main.post { activity.startActivity(Intent.createChooser(send, null)) }
    return true
  }

  /** THE PERSON THEMSELVES: a fingerprint, the face, or the screen lock — the system's prompt */
  private fun verify(reason: String): Boolean = await<Boolean> { done ->
    main.post {
      val prompt = android.hardware.biometrics.BiometricPrompt.Builder(activity).setTitle(reason)
        .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
          android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL).build()
      prompt.authenticate(android.os.CancellationSignal(), context.mainExecutor,
        object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
          override fun onAuthenticationSucceeded(r: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) =
            done(true)
          override fun onAuthenticationError(code: Int, message: CharSequence) = done(false)
        })
    }
  } ?: false

  private fun open(url: String): Boolean = try {
    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    main.post { try { activity.startActivity(i) } catch (e: Exception) { } }
    true
  } catch (e: Exception) { false }

  // SECRETS: AES-GCM by a key that never leaves the Keystore; the IV and the ciphertext kept, in Base64
  private fun secretKey(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (ks.getKey("okay-faces", null) as? SecretKey)?.let { return it }
    val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    g.init(KeyGenParameterSpec.Builder("okay-faces", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
    return g.generateKey()
  }
  private fun encrypt(s: String): String {
    val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
    return Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), Base64.NO_WRAP)
  }
  private fun decrypt(s: String): String? = try {
    val b = Base64.decode(s, Base64.NO_WRAP)
    val c = Cipher.getInstance("AES/GCM/NoPadding")
    c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
    String(c.doFinal(b.copyOfRange(12, b.size)))
  } catch (e: Exception) { null }

  companion object {
    /** the phone, Android's, for every app of this process: called while the activity is created */
    fun install(activity: ComponentActivity) { Shell.device(DeviceBridge(activity)) }
  }
}
