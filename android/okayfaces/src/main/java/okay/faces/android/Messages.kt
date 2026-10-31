// NOTIFICATIONS BY FCM, the app open: FCM shows a notification itself when the app is in the background; in front, it
// hands it here, and it is shown the same way (the channel `okay-faces`)
package okay.faces.android

import android.app.NotificationChannel
import android.app.NotificationManager
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class OkayMessages : FirebaseMessagingService() {
  override fun onMessageReceived(message: RemoteMessage) {
    val n = message.notification ?: return
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel("okay-faces", "okay-faces", NotificationManager.IMPORTANCE_HIGH))
    manager.notify((System.nanoTime() % Int.MAX_VALUE).toInt(),
      android.app.Notification.Builder(this, "okay-faces").setContentTitle(n.title).setContentText(n.body)
        .setSmallIcon(android.R.drawable.ic_dialog_info).setAutoCancel(true).build())
  }
  // a new token: registered again the next time the app enables notifications (`Accounts.enablePush`)
  override fun onNewToken(token: String) {}
}
