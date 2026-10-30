// THE SHELL: the examples' app — the application named by the intent's `app` (else the list), its phone Android's.
// `reset`: what the app kept forgotten first, so a test starts from nothing.
package okay.faces.android.shell

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import okay.faces.android.DeviceBridge
import okay.faces.android.OkayFacesView

class MainActivity : ComponentActivity() {
  override fun onCreate(saved: Bundle?) {
    super.onCreate(saved)
    if (intent.getBooleanExtra("reset", false)) {
      getSharedPreferences("okay-faces", Context.MODE_PRIVATE).edit().clear().commit()
    }
    DeviceBridge.install(this)
    enableEdgeToEdge()
    val app = intent.getStringExtra("app") ?: "okay.faces.mobile.example.TodoApp"
    setContent { OkayFacesView(app) }
  }
}
