package cloud.airchive.personal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import cloud.airchive.shared.AirchiveApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AirchiveApp() }
    }
}
