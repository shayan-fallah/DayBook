package app.calorietracker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.calorietracker.data.Api
import app.calorietracker.data.ApiException
import app.calorietracker.data.CalendarsReply
import app.calorietracker.data.DeleteCountReply
import app.calorietracker.data.DeleteProjectReply
import app.calorietracker.data.EntriesReply
import app.calorietracker.data.GoogleCalendar
import app.calorietracker.data.GoogleEvent
import app.calorietracker.data.GoogleEventsReply
import app.calorietracker.data.GoogleStatus
import app.calorietracker.data.OkReply
import app.calorietracker.data.PlanAddReply
import app.calorietracker.data.PlanBlock
import app.calorietracker.data.PlanReply
import app.calorietracker.data.Project
import app.calorietracker.data.ProjectsReply
import app.calorietracker.data.StopReply
import app.calorietracker.data.TaskItem
import app.calorietracker.data.TasksReply
import app.calorietracker.data.TimeEntry
import app.calorietracker.data.TimerReply
import app.calorietracker.data.UrlReply
import app.calorietracker.data.WorkPrefs
import app.calorietracker.data.jsonBody
import app.calorietracker.ui.addMonths
import app.calorietracker.ui.dayStart
import app.calorietracker.ui.hrs
import app.calorietracker.ui.monthStart
import app.calorietracker.ui.startOfWeek
import app.calorietracker.ui.yearStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

/** One week of the planner: blocks, logged time and Google events. */
data class PlanWeek(
    val start: LocalDate,
    val blocks: List<PlanBlock> = emptyList(),
    val entries: List<TimeEntry> = emptyList(),
    val google: List<GoogleEvent> = emptyList(),
    val googleError: String? = null,
    val googleOn: Boolean = false,
)

/** What the report covers: [from, to) and everything logged or planned in it. */
data class ReportData(
    val preset: String, val from: LocalDate, val to: LocalDate,
    val entries: List<TimeEntry>, val blocks: List<PlanBlock>,
)

/** Time tracking, projects, to-dos, the planner and Google Calendar: state and server calls. */
class WorkStore(
    private val scope: CoroutineScope,
    private val api: Api,
    private val onError: (ApiException) -> Unit,
    private val toast: (String) -> Unit,
) {
    var prefs by mutableStateOf(WorkPrefs())
        private set
    var projects by mutableStateOf<List<Project>>(emptyList())
        private set
    var tasks by mutableStateOf<List<TaskItem>>(emptyList())
        private set
    var running by mutableStateOf<TimeEntry?>(null)
        private set
    /** Server clock minus phone clock, in seconds, so timers are right even when the phone's clock isn't. */
    private var skew by mutableLongStateOf(0L)
    var recent by mutableStateOf<List<TimeEntry>?>(null)
        private set
    var recentDays by mutableIntStateOf(14)
        private set

    // Timer fields while nothing runs
    var draftDescription by mutableStateOf("")
    var draftProject by mutableStateOf<Long?>(null)
    var draftTask by mutableStateOf<Long?>(null)

    // Which section of the Time and Plan tabs is showing
    var timeTab by mutableStateOf("timer")
    var planTab by mutableStateOf("schedule")

    var planDate by mutableStateOf(LocalDate.now())
    var week by mutableStateOf<PlanWeek?>(null)
        private set
    var reportPreset by mutableStateOf("week")
        private set
    var reportAnchor by mutableStateOf(LocalDate.now())
        private set
    var report by mutableStateOf<ReportData?>(null)
        private set
    var todoFilter by mutableStateOf("all")

    var google by mutableStateOf<GoogleStatus?>(null)
        private set
    var calendars by mutableStateOf<List<GoogleCalendar>?>(null)
        private set
    var icsUrl by mutableStateOf<String?>(null)
        private set

    fun now(): Long = System.currentTimeMillis() / 1000 + skew
    fun project(id: Long?): Project? = if (id == null) null else projects.firstOrNull { it.id == id }
    fun task(id: Long?): TaskItem? = if (id == null) null else tasks.firstOrNull { it.id == id }
    fun projectName(id: Long?): String = if (id == null) "No project" else project(id)?.name ?: "Deleted project"
    fun projectColor(id: Long?): String = project(id)?.color ?: app.calorietracker.ui.NO_PROJECT_COLOR

    /** What a time entry or block is about, for its title line. */
    fun titleOf(description: String, projectId: Long?, taskId: Long?): String =
        description.ifBlank { task(taskId)?.title ?: projectName(projectId) }

    private fun launchSafe(block: suspend () -> Unit) = scope.launch {
        try {
            block()
        } catch (e: ApiException) {
            onError(e)
        }
    }

    private suspend fun act(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: ApiException) {
        onError(e)
        false
    }

    // ------------------------------------------------------------ start, reset

    fun start() = launchSafe {
        val p = scope.async { api.get<WorkPrefs>("/api/prefs") }
        val pr = scope.async { api.get<ProjectsReply>("/api/projects") }
        val t = scope.async { api.get<TasksReply>("/api/tasks") }
        val tm = scope.async { api.get<TimerReply>("/api/time/timer") }
        prefs = p.await()
        projects = pr.await().projects
        tasks = t.await().tasks
        val timer = tm.await()
        syncClock(timer.now)
        running = timer.running
    }

    fun reset() {
        projects = emptyList()
        tasks = emptyList()
        running = null
        recent = null
        week = null
        report = null
        google = null
        calendars = null
        icsUrl = null
        planDate = LocalDate.now()
    }

    private fun syncClock(serverNow: Long) {
        if (serverNow > 0) skew = serverNow - System.currentTimeMillis() / 1000
    }

    // ------------------------------------------------------------ prefs, projects, tasks

    fun loadPrefs() = launchSafe { prefs = api.get("/api/prefs") }

    suspend fun savePrefs(body: JsonObject): Boolean = act {
        prefs = api.put("/api/prefs", body)
        week = null
        toast("Saved")
    }

    fun loadProjects() = launchSafe { projects = api.get<ProjectsReply>("/api/projects").projects }

    suspend fun saveProject(id: Long?, body: JsonObject): Boolean = act {
        if (id == null) api.post<Project>("/api/projects", body) else api.patch<Project>("/api/projects/$id", body)
        projects = api.get<ProjectsReply>("/api/projects").projects
        toast(if (id == null) "Project added" else "Project saved")
    }

    suspend fun deleteProject(p: Project): Boolean = act {
        api.delete<DeleteProjectReply>("/api/projects/${p.id}")
        projects = api.get<ProjectsReply>("/api/projects").projects
        tasks = api.get<TasksReply>("/api/tasks").tasks
        toast("Project deleted")
    }

    fun loadTasks() = launchSafe { tasks = api.get<TasksReply>("/api/tasks").tasks }

    suspend fun saveTask(id: Long?, body: JsonObject): Boolean = act {
        if (id == null) api.post<TaskItem>("/api/tasks", body) else api.patch<TaskItem>("/api/tasks/$id", body)
        tasks = api.get<TasksReply>("/api/tasks").tasks
    }

    fun toggleTask(t: TaskItem) {
        val flipped = t.copy(done = !t.done, doneAt = if (t.done) null else now())
        tasks = tasks.map { if (it.id == t.id) flipped else it }
        scope.launch {
            try {
                val saved: TaskItem = api.patch("/api/tasks/${t.id}", jsonBody("done" to flipped.done))
                tasks = tasks.map { if (it.id == t.id) saved else it }
                if (saved.done) toast("Done: ${t.title}")
            } catch (e: ApiException) {
                tasks = tasks.map { if (it.id == t.id) t else it }
                onError(e)
            }
        }
    }

    suspend fun deleteTask(t: TaskItem): Boolean = act {
        api.delete<OkReply>("/api/tasks/${t.id}")
        tasks = tasks.filter { it.id != t.id }
        toast("Deleted")
    }

    // ------------------------------------------------------------ timer and time log

    fun loadTimer() = launchSafe {
        val r: TimerReply = api.get("/api/time/timer")
        syncClock(r.now)
        running = r.running
    }

    fun loadRecent(moreDays: Int = 0) = launchSafe {
        recentDays += moreDays
        val today = LocalDate.now()
        val from = dayStart(today.minusDays((recentDays - 1).toLong()))
        val to = dayStart(today.plusDays(1))
        val r: EntriesReply = api.get("/api/time/entries?from=$from&to=$to")
        syncClock(r.now)
        recent = r.entries
        running = r.running
    }

    fun startTimer(description: String, projectId: Long?, taskId: Long?) = launchSafe {
        val r: TimerReply = api.post(
            "/api/time/timer/start",
            jsonBody("description" to description, "project_id" to projectId, "task_id" to taskId),
        )
        syncClock(r.now)
        running = r.running
        draftDescription = ""
        draftProject = null
        draftTask = null
        toast("Timer started: ${titleOf(description, projectId, taskId)}")
        afterTimeChange()
    }

    fun stopTimer() = launchSafe {
        val r: StopReply = api.post("/api/time/timer/stop")
        running = null
        r.stopped?.let { toast("Logged ${hrs((it.endTs ?: it.startTs) - it.startTs)}") }
        afterTimeChange()
    }

    /** Change what the running timer is about. */
    fun updateRunning(description: String, projectId: Long?, taskId: Long?) {
        val r = running ?: return
        launchSafe {
            running = api.patch(
                "/api/time/entries/${r.id}",
                jsonBody("description" to description, "project_id" to projectId, "task_id" to taskId),
            )
            if (recent != null) loadRecent()
        }
    }

    suspend fun saveEntry(id: Long?, body: JsonObject): Boolean = act {
        if (id == null) api.post<TimeEntry>("/api/time/entries", body)
        else {
            val saved: TimeEntry = api.patch("/api/time/entries/$id", body)
            if (running?.id == id) running = saved
        }
        toast(if (id == null) "Time added" else "Saved")
        afterTimeChange()
    }

    suspend fun deleteEntry(e: TimeEntry): Boolean = act {
        api.delete<OkReply>("/api/time/entries/${e.id}")
        if (running?.id == e.id) running = null
        toast("Entry deleted")
        afterTimeChange()
    }

    private fun afterTimeChange() {
        if (recent != null) loadRecent()
        if (report != null) loadReport()
        if (week != null) loadWeek()
        loadProjects()
        loadTasks()
    }

    // ------------------------------------------------------------ reports

    fun setReport(preset: String) {
        reportPreset = preset
        reportAnchor = LocalDate.now()
        loadReport()
    }

    fun shiftReport(k: Int) {
        reportAnchor = when (reportPreset) {
            "day" -> reportAnchor.plusDays(k.toLong())
            "week" -> reportAnchor.plusWeeks(k.toLong())
            "month" -> prefs.addMonths(prefs.monthStart(reportAnchor), k)
            else -> prefs.addMonths(prefs.yearStart(reportAnchor), 12 * k)
        }
        loadReport()
    }

    fun reportRange(): Pair<LocalDate, LocalDate> {
        val a = reportAnchor
        return when (reportPreset) {
            "day" -> a to a.plusDays(1)
            "week" -> prefs.startOfWeek(a).let { it to it.plusDays(7) }
            "month" -> prefs.monthStart(a).let { it to prefs.addMonths(it, 1) }
            else -> prefs.yearStart(a).let { it to prefs.addMonths(it, 12) }
        }
    }

    fun loadReport() = launchSafe {
        val preset = reportPreset
        val (a, b) = reportRange()
        val from = dayStart(a)
        val to = dayStart(b)
        val e = scope.async { api.get<EntriesReply>("/api/time/entries?from=$from&to=$to") }
        val p = scope.async { api.get<PlanReply>("/api/plan?from=$from&to=$to") }
        val entries = e.await()
        val plan = p.await()
        syncClock(entries.now)
        if (preset == reportPreset && reportRange().first == a) report = ReportData(preset, a, b, entries.entries, plan.blocks)
    }

    // ------------------------------------------------------------ planner

    fun changePlanDate(d: LocalDate) {
        planDate = d
        loadWeek()
    }

    fun loadWeek() = launchSafe {
        val w0 = prefs.startOfWeek(planDate)
        val from = dayStart(w0)
        val to = dayStart(w0.plusDays(7))
        if (week?.start != w0) week = null
        val p = scope.async { api.get<PlanReply>("/api/plan?from=$from&to=$to") }
        val e = scope.async { api.get<EntriesReply>("/api/time/entries?from=$from&to=$to") }
        if (tasks.isEmpty()) loadTasks()
        val plan = p.await()
        val entries = e.await()
        syncClock(entries.now)
        running = entries.running
        if (prefs.startOfWeek(planDate) != w0) return@launchSafe
        val old = week?.takeIf { it.start == w0 }
        week = PlanWeek(w0, plan.blocks, entries.entries, old?.google ?: emptyList(), old?.googleError, plan.google)
        if (plan.google) loadGoogleEvents(w0, from, to)
    }

    private fun loadGoogleEvents(w0: LocalDate, from: Long, to: Long) = scope.launch {
        val r = try {
            api.get<GoogleEventsReply>("/api/google/events?from=$from&to=$to")
        } catch (e: ApiException) {
            GoogleEventsReply(error = e.message)
        }
        var blocks = week?.takeIf { it.start == w0 }?.blocks ?: return@launch
        if (r.changed > 0) { // moved or deleted in Google
            try {
                blocks = api.get<PlanReply>("/api/plan?from=$from&to=$to").blocks
            } catch (_: ApiException) {
            }
        }
        week = week?.takeIf { it.start == w0 }?.copy(blocks = blocks, google = r.events, googleError = r.error)
    }

    /** Add or update a planned block. [repeatStarts] adds copies of a new block at these times. */
    suspend fun saveBlock(id: Long?, body: JsonObject): Boolean = act {
        if (id == null) {
            val r: PlanAddReply = api.post("/api/plan", body)
            toast(if (r.ids.size > 1) "Planned ${r.ids.size} times" else "Planned")
        } else {
            api.patch<PlanBlock>("/api/plan/$id", body)
            toast("Saved")
        }
        loadWeek()
        if (report != null) loadReport()
    }

    suspend fun deleteBlock(b: PlanBlock, following: Boolean): Boolean = act {
        val r: DeleteCountReply = api.delete("/api/plan/${b.id}" + if (following) "?following=1" else "")
        toast(if (r.deleted > 1) "Deleted ${r.deleted} planned blocks" else "Deleted")
        loadWeek()
    }

    // ------------------------------------------------------------ Google Calendar and the calendar feed

    fun loadGoogle() = launchSafe {
        val g: GoogleStatus = api.get("/api/google")
        google = g
        if (g.connected && !g.needsReauth && calendars == null) loadCalendars()
    }

    private fun loadCalendars() = launchSafe { calendars = api.get<CalendarsReply>("/api/google/calendars").calendars }

    /** The Google page to open in the browser, or null on error. */
    suspend fun googleConnectUrl(): String? = try {
        api.post<UrlReply>("/api/google/connect").url
    } catch (e: ApiException) {
        onError(e)
        null
    }

    suspend fun saveGoogle(body: JsonObject): Boolean = act {
        google = api.put("/api/google", body)
        week = week?.copy(google = emptyList())
    }

    fun syncGoogle() = launchSafe {
        try {
            val g: GoogleStatus = api.post("/api/google/sync")
            google = g
            toast(if (g.pushed > 0) "Sent ${g.pushed} change${if (g.pushed == 1) "" else "s"} to Google" else "Everything is up to date")
        } catch (e: ApiException) {
            loadGoogle()
            throw e
        }
    }

    fun disconnectGoogle() = launchSafe {
        api.delete<OkReply>("/api/google")
        calendars = null
        toast("Disconnected")
        google = api.get("/api/google")
    }

    fun loadIcs() = launchSafe { icsUrl = api.get<UrlReply>("/api/ics").url }

    fun resetIcs() = launchSafe {
        icsUrl = api.post<UrlReply>("/api/ics/reset").url
        toast("New link made")
    }
}
