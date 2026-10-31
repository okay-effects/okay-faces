// The whole of the app's Kotlin: the phone given to its Scala, its Scala opened by the class name of its `MobileApp`
package app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import okay.faces.android.DeviceBridge
import okay.faces.android.OkayFacesView

class MainActivity : ComponentActivity() {
  override fun onCreate(saved: Bundle?) {
    super.onCreate(saved)
    DeviceBridge.install(this)
    enableEdgeToEdge()
    setContent { OkayFacesView("app.CounterApp") }
  }
}
