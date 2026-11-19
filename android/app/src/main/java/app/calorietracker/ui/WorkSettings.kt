package app.calorietracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.calorietracker.AppViewModel
import app.calorietracker.data.jsonBody
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Settings for time tracking and the planner: work goal, calendar, Google Calendar and the calendar feed. */
@Composable
fun WorkSettingsCards(vm: AppViewModel) {
    val work = vm.work
    LaunchedEffect(Unit) {
        work.loadPrefs()
        work.loadGoogle()
        work.loadIcs()
    }
    PrefsCard(vm)
    GoogleCard(vm)
    FeedCard(vm)
}

@Composable
private fun PrefsCard(vm: AppViewModel) {
    val work = vm.work
    val prefs = work.prefs
    val scope = rememberCoroutineScope()
    var goal by remember(prefs) { mutableStateOf(prefs.workGoal.trimmed()) }
    var weekStart by remember(prefs) { mutableStateOf(prefs.weekStart) }
    var calendar by remember(prefs) { mutableStateOf(prefs.calendar) }
    var busy by remember { mutableStateOf(false) }
    AppCard {
        CardTitle("Time and planner")
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField(goal, { goal = it }, "Daily work goal", Modifier.fillMaxWidth(), suffix = "hours")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Picker(
                    "Week starts on",
                    listOf("auto" to "Automatic", "sat" to "Saturday", "sun" to "Sunday", "mon" to "Monday"),
                    weekStart, Modifier.weight(1f),
                ) { weekStart = it }
                Picker("Dates", listOf("gregorian" to "Gregorian", "jalali" to "Shamsi (Solar Hijri)"), calendar, Modifier.weight(1f)) {
                    calendar = it
                }
            }
            Button(
                enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp),
                onClick = {
                    val g = if (goal.isBlank()) 0.0 else parseNum(goal)
                    if (g == null || g < 0 || g > 24) return@Button vm.toast("The work goal is in hours, 0 to 24")
                    busy = true
                    scope.launch {
                        work.savePrefs(jsonBody("work_goal" to g, "week_start" to weekStart, "calendar" to calendar))
                        busy = false
                    }
                },
            ) { Text("Save") }
        }
    }
}

@Composable
private fun GoogleCard(vm: AppViewModel) {
    val work = vm.work
    val g = work.google
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    var confirmDisconnect by remember { mutableStateOf(false) }

    fun connect() {
        scope.launch {
            work.googleConnectUrl()?.let { url ->
                runCatching { uri.openUri(url) }.onFailure { vm.toast("Couldn't open the browser") }
            }
        }
    }

    AppCard {
        CardTitle("Google Calendar")
        Spacer(Modifier.height(6.dp))
        when {
            g == null -> SubText("Loading…")
            !g.available -> {
                SubText(
                    "See your planned blocks in Google Calendar, and your Google events in the planner. To turn this on, " +
                        "the server needs a Google sign-in key (GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET). The README explains how.",
                )
                Spacer(Modifier.height(6.dp))
                SubText("Until then, the calendar feed below works with any calendar app.", color = p.muted)
            }
            !g.connected -> {
                SubText("Send your planned blocks to Google Calendar and see your Google events next to them in the planner.")
                Spacer(Modifier.height(10.dp))
                Button(onClick = { connect() }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Connect Google Calendar") }
                SubText("Opens your browser. Come back to the app when it says connected.", color = p.muted)
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(buildString { append("Connected as "); append(g.email ?: "your Google account") }, style = MaterialTheme.typography.bodyMedium)
                if (g.needsReauth) {
                    Surface(color = p.critical.copy(alpha = 0.12f), shape = MaterialTheme.shapes.medium) {
                        Text(
                            "Google stopped accepting this connection (it expired or was removed). Connect again to keep syncing.",
                            modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Button(onClick = { connect() }, modifier = Modifier.fillMaxWidth()) { Text("Connect again") }
                } else {
                    val cals = work.calendars.orEmpty().filter { it.writable }.let { list ->
                        if (list.none { it.id == g.calendarId }) listOf(app.calorietracker.data.GoogleCalendar(g.calendarId ?: "", g.calendarId ?: "")) + list else list
                    }
                    Picker(
                        "Put planned blocks in",
                        cals.map { it.id to (if (it.primary) "${it.name} (main)" else it.name) },
                        g.calendarId ?: "",
                    ) { id ->
                        scope.launch { if (work.saveGoogle(jsonBody("calendar_id" to id))) vm.toast("Planned blocks will move to that calendar") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Show my Google events in the planner", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(g.showEvents, { on -> scope.launch { work.saveGoogle(jsonBody("show_events" to on)) } })
                    }
                    val status = buildList {
                        g.lastSync?.let {
                            add("Last synced " + Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault())
                                .format(DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US)) + ".")
                        }
                        if (g.pending > 0) add("${g.pending} change${if (g.pending == 1) "" else "s"} waiting to be sent.")
                    }
                    if (status.isNotEmpty()) SubText(status.joinToString(" "), color = p.muted)
                    g.lastError?.let { SubText("Last problem: $it", color = p.critical) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!g.needsReauth) OutlinedButton(onClick = { work.syncGoogle() }) { Text("Sync now") }
                    OutlinedButton(
                        onClick = { confirmDisconnect = true },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Disconnect") }
                }
            }
        }
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect Google Calendar?") },
            text = { Text("Events already in Google stay there.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    work.disconnectGoogle()
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FeedCard(vm: AppViewModel) {
    val work = vm.work
    val clipboard = LocalClipboardManager.current
    var confirmReset by remember { mutableStateOf(false) }
    AppCard {
        CardTitle("Calendar feed")
        SubText(
            "A private link to subscribe to in any calendar app (Apple, Outlook, Google…). It has your planned blocks " +
                "and dated to-dos and updates every few hours. Anyone with the link can see them.",
        )
        Spacer(Modifier.height(8.dp))
        Text(work.icsUrl ?: "Loading…", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(enabled = work.icsUrl != null, onClick = {
                work.icsUrl?.let {
                    clipboard.setText(AnnotatedString(it))
                    vm.toast("Link copied")
                }
            }) { Text("Copy link") }
            TextButton(onClick = { confirmReset = true }) { Text("Make a new link") }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Make a new link?") },
            text = { Text("Calendars subscribed to the old link stop updating.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    work.resetIcs()
                }) { Text("Make new link") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}
