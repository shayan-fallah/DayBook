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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.calorietracker.AppViewModel
import app.calorietracker.data.TelegramStatus
import app.calorietracker.data.jsonBody
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Settings for time tracking and the planner: work goal, calendar, Telegram, Google Calendar and the calendar feed. */
@Composable
fun WorkSettingsCards(vm: AppViewModel) {
    val work = vm.work
    LaunchedEffect(Unit) {
        work.loadPrefs()
        work.loadGoogle()
        work.loadIcs()
        work.loadTelegram()
    }
    PrefsCard(vm)
    TelegramCard(vm)
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

// ---------------------------------------------------------------- Telegram

private val TG_LEADS = listOf(
    "-1" to "Off", "0" to "When it starts", "5" to "5 minutes before", "10" to "10 minutes before",
    "15" to "15 minutes before", "30" to "30 minutes before",
)
private val TG_TIMER = listOf("0" to "Off", "2" to "2 hours", "3" to "3 hours", "4" to "4 hours", "6" to "6 hours", "8" to "8 hours")

@Composable
private fun TelegramCard(vm: AppViewModel) {
    val work = vm.work
    val t = work.telegram
    val p = LocalPalette.current
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var showBot by remember(t?.configured) { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    // While we wait for the user to press Start in Telegram, check every few seconds.
    LaunchedEffect(t?.configured, t?.linked) {
        if (t == null || !t.configured || t.linked) return@LaunchedEffect
        while (true) {
            delay(3000)
            work.loadTelegram(quiet = true)
            if (work.telegram?.linked == true) vm.toast("Telegram connected")
        }
    }

    AppCard {
        CardTitle("Telegram")
        Spacer(Modifier.height(6.dp))
        when {
            t == null -> SubText("Loading…")
            !t.configured -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SubText(
                    "Get an alarm for each to-do you give a reminder time, a heads-up before planned time, and a short " +
                        "morning summary. You can also add to-dos by messaging the bot.",
                )
                SubText(
                    "1. In Telegram, open @BotFather, send /newbot and follow the steps.\n" +
                        "2. Copy the token it gives you and paste it here.",
                    color = p.ink2,
                )
                TelegramBotForm(vm, t)
            }
            !t.linked -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Bot @${t.bot} is ready. Open it in Telegram and press Start, so it knows where to send your reminders.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { t.linkUrl?.let { runCatching { uri.openUri(it) }.onFailure { vm.toast("Couldn't open Telegram") } } },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) { Text("Open @${t.bot} in Telegram") }
                SubText("Or send the bot this message: /start ${t.linkCode}. This screen updates by itself.", color = p.muted)
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString("/start ${t.linkCode}"))
                    vm.toast("Copied")
                }) { Text("Copy the message") }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sending to ${t.chat ?: "your chat"} through @${t.bot}.", style = MaterialTheme.typography.bodyMedium)
                TelegramSendForm(vm, t)
                SubText(
                    "Give a to-do a “Remind me” time to get an alarm for it. In the chat, send any text to add a to-do, " +
                        "or use /today, /todo, /remind 18:30 Call mom, /timer and /stop.",
                    color = p.muted,
                )
            }
        }
        if (t?.configured == true) {
            t.lastError?.let { err ->
                val at = t.lastErrorAt?.let {
                    Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US))
                }
                Spacer(Modifier.height(8.dp))
                SubText("Last problem${at?.let { " ($it)" } ?: ""}: $err", color = p.critical)
            }
            TextButton(onClick = { showBot = !showBot }) { Text(if (showBot) "Hide bot and proxy" else "Bot and proxy") }
            if (showBot) TelegramBotForm(vm, t)
            OutlinedButton(
                onClick = { confirmDisconnect = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Disconnect Telegram") }
        }
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Disconnect Telegram?") },
            text = { Text("The bot stops sending you reminders. You can connect again later.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    work.disconnectTelegram()
                }) { Text("Disconnect") }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
        )
    }
}

/** The bot token and the SOCKS5 proxy. The server checks them with Telegram before saving. */
@Composable
private fun TelegramBotForm(vm: AppViewModel, t: TelegramStatus) {
    val work = vm.work
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val px = t.proxy
    var token by remember { mutableStateOf("") }
    var host by remember(px) { mutableStateOf(px?.host ?: "") }
    var port by remember(px) { mutableStateOf(px?.port?.toString() ?: "") }
    var user by remember(px) { mutableStateOf(px?.username ?: "") }
    var pass by remember { mutableStateOf("") }
    var showProxy by remember { mutableStateOf(px != null || !t.configured) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val ascii = KeyboardOptions(keyboardType = KeyboardType.Ascii)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            token, { token = it.trim() }, label = { Text("Bot token") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (t.configured) "Saved (${t.tokenHint})" else "123456789:AAH…") },
            supportingText = if (t.configured) ({ Text("Paste a new one to change it") }) else null,
            keyboardOptions = ascii,
        )
        TextButton(onClick = { showProxy = !showProxy }) { Text(if (showProxy) "Proxy (SOCKS5) ▴" else "Proxy (SOCKS5) ▾") }
        if (showProxy) {
            SubText(
                "If this server can’t reach Telegram directly (for example where it’s blocked), it can go through a SOCKS5 " +
                    "proxy. Leave the address empty to connect directly. You can also paste a whole socks5:// link into Address.",
                color = p.muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    host, { host = it.trim() }, label = { Text("Address") }, singleLine = true,
                    modifier = Modifier.weight(2f), placeholder = { Text("127.0.0.1") }, keyboardOptions = ascii,
                )
                OutlinedTextField(
                    port, { v -> port = v.filter { it.isDigit() }.take(5) }, label = { Text("Port") }, singleLine = true,
                    modifier = Modifier.weight(1f), placeholder = { Text("1080") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    user, { user = it.trim() }, label = { Text("Username") }, singleLine = true,
                    modifier = Modifier.weight(1f), placeholder = { Text("Optional") }, keyboardOptions = ascii,
                )
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.weight(1f),
                    placeholder = { Text(if (px?.hasPassword == true) "Saved" else "Optional") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        }
        error?.let { SubText(it, color = p.critical) }
        Button(
            enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp),
            onClick = {
                if (!t.configured && token.isBlank()) {
                    error = "Paste the bot token from @BotFather"
                    return@Button
                }
                val proxy = if (host.isBlank()) null else jsonBody(
                    *buildList {
                        add("host" to host)
                        add("port" to port.ifBlank { null })
                        add("username" to user)
                        if (pass.isNotEmpty() || px?.hasPassword != true) add("password" to pass) // empty keeps the saved one
                    }.toTypedArray(),
                )
                val body = jsonBody(
                    *buildList {
                        add("tz" to ZoneId.systemDefault().id)
                        add("proxy" to proxy)
                        if (token.isNotBlank()) add("token" to token)
                    }.toTypedArray(),
                )
                busy = true
                error = null
                scope.launch {
                    error = work.saveTelegramBot(body)
                    busy = false
                    if (error == null) {
                        token = ""
                        pass = ""
                        vm.toast(if (work.telegram?.linked == true) "Saved" else "Connected to @${work.telegram?.bot}")
                    }
                }
            },
        ) { Text(if (busy) "Checking…" else "Save and check") }
    }
}

/** What to send: morning summary, heads-up before planned time, timer nudge, ringing again. */
@Composable
private fun TelegramSendForm(vm: AppViewModel, t: TelegramStatus) {
    val work = vm.work
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    val s = t.settings
    var brief by remember(s) { mutableStateOf(s.brief) }
    var briefTime by remember(s) { mutableStateOf(runCatching { LocalTime.parse(s.briefTime) }.getOrDefault(LocalTime.of(8, 0))) }
    var lead by remember(s) { mutableStateOf(s.lead.toString()) }
    var timer by remember(s) { mutableStateOf(s.timerHours.toString()) }
    var again by remember(s) { mutableStateOf(s.ringAgain) }
    var busy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Morning summary", style = MaterialTheme.typography.bodyMedium)
                SubText("Today’s plan and the to-dos that are due. Skipped on days with nothing on.", color = p.muted)
            }
            Switch(brief, { brief = it })
        }
        if (brief) TimeField("Summary time", briefTime, Modifier.fillMaxWidth()) { briefTime = it }
        Picker("Heads-up before planned time", TG_LEADS, lead) { lead = it }
        Picker("Nudge me when a timer is still running after", TG_TIMER, timer) { timer = it }
        SubText("Not between 23:00 and 07:00.", color = p.muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Ring again after 5 minutes if I don’t answer an alarm",
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            )
            Switch(again, { again = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    work.saveTelegram(
                        jsonBody(
                            "brief" to brief, "brief_time" to hm(briefTime), "lead" to lead.toInt(),
                            "timer_hours" to timer.toInt(), "ring_again" to again, "tz" to ZoneId.systemDefault().id,
                        ),
                    )
                    busy = false
                }
            }) { Text("Save") }
            OutlinedButton(onClick = { work.testTelegram() }) { Text("Send a test message") }
        }
    }
}
