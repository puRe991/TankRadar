package de.tankradar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.tankradar.app.ui.TankRadarNavigation
import de.tankradar.app.ui.theme.TankRadarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TankRadarTheme {
                TankRadarNavigation()
            }
        }
    }
}
