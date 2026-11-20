package app.calorietracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.calorietracker.ui.CalorieApp
import app.calorietracker.ui.CalorieTheme
import app.calorietracker.widget.WidgetRepo

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) Nav.from(intent)
        setContent {
            CalorieTheme {
                CalorieApp()
            }
        }
    }

    /** A widget opened the app while it was already running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Nav.from(intent)
    }

    /** Leaving the app: bring the home-screen widgets up to date with what changed here. */
    override fun onStop() {
        super.onStop()
        WidgetRepo.requestRefresh(this)
    }
}
