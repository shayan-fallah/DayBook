package app.calorietracker

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where a home-screen widget asked the app to open: "today", "add" (add food), "timer", "plan",
 * "todo" or "newtask". MainActivity puts the request here; the app screen picks it up and clears it.
 */
object Nav {
    const val EXTRA_OPEN = "open"

    private val _target = MutableStateFlow<String?>(null)
    val target: StateFlow<String?> = _target

    fun from(intent: Intent?) {
        val open = intent?.getStringExtra(EXTRA_OPEN) ?: return
        intent.removeExtra(EXTRA_OPEN)
        _target.value = open
    }

    fun consume() {
        _target.value = null
    }
}
