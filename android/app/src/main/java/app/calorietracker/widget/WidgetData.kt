package app.calorietracker.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.calorietracker.data.Api
import app.calorietracker.data.ApiException
import app.calorietracker.data.Day
import app.calorietracker.data.EntriesReply
import app.calorietracker.data.GoogleEvent
import app.calorietracker.data.GoogleEventsReply
import app.calorietracker.data.PlanReply
import app.calorietracker.data.Project
import app.calorietracker.data.ProjectsReply
import app.calorietracker.data.TaskItem
import app.calorietracker.data.TasksReply
import app.calorietracker.data.TimeEntry
import app.calorietracker.data.WorkPrefs
import app.calorietracker.ui.NO_PROJECT_COLOR
import app.calorietracker.ui.dayStart
import app.calorietracker.ui.overlap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.util.concurrent.TimeUnit

// Everything the home-screen widgets show is fetched in one go, kept as a snapshot on the phone
// (so widgets draw instantly, also offline) and refreshed when you leave the app, every 30 minutes,
// and when you tap ↻ or an action on a widget.

/** One line in the agenda: a planned block or a Google Calendar event. */
@Serializable
data class AgendaItem(
    val key: String,
    val title: String,
    val sub: String = "",
    val color: String = NO_PROJECT_COLOR,
    val start: Long = 0,
    val end: Long = 0,
    /** All-day events: the day (yyyy-mm-dd) instead of start and end. */
    val day: String? = null,
    /** A block planned in Daybook (these can start the timer). */
    val block: Boolean = false,
    val projectId: Long? = null,
    val taskId: Long? = null,
    val description: String = "",
)

@Serializable
data class DaySummary(
    val date: String,
    val kcal: Double = 0.0,
    /** Calorie goal plus the workout calories added to it. */
    val budget: Double = 0.0,
    val protein: Double = 0.0, val proteinGoal: Double = 0.0,
    val carbs: Double = 0.0, val carbsGoal: Double = 0.0,
    val fat: Double = 0.0, val fatGoal: Double = 0.0,
)

@Serializable
data class WidgetSnapshot(
    /** "empty" before the first load, "ok", or "logged_out". */
    val state: String = "empty",
    /** Phone clock, seconds. */
    val fetchedAt: Long = 0,
    /** Server clock minus phone clock, seconds. */
    val skew: Long = 0,
    val error: String? = null,
    val prefs: WorkPrefs = WorkPrefs(),
    val projects: List<Project> = emptyList(),
    /** Open to-dos. */
    val tasks: List<TaskItem> = emptyList(),
    val running: TimeEntry? = null,
    /** Finished time entries of the last two weeks, newest first. */
    val entries: List<TimeEntry> = emptyList(),
    /** Planned blocks and Google events from today until a week from now. */
    val agenda: List<AgendaItem> = emptyList(),
    val day: DaySummary? = null,
) {
    fun now() = System.currentTimeMillis() / 1000 + skew
    fun project(id: Long?): Project? = if (id == null) null else projects.firstOrNull { it.id == id }
    fun projectName(id: Long?) = if (id == null) "No project" else project(id)?.name ?: "Deleted project"
    fun color(id: Long?) = project(id)?.color ?: NO_PROJECT_COLOR
    fun titleOf(e: TimeEntry) = e.description.ifBlank {
        tasks.firstOrNull { it.id == e.taskId }?.title ?: projectName(e.projectId)
    }

    /** The newest finished entry, which "Continue" starts again. */
    fun last(): TimeEntry? = entries.firstOrNull()

    /** Seconds tracked today, including the running timer up to now. */
    fun trackedToday(): Long {
        val today = LocalDate.now()
        val a = dayStart(today)
        val b = dayStart(today.plusDays(1))
        val n = now()
        val done = entries.sumOf { overlap(it.startTs, it.endTs ?: it.startTs, a, b) }
        return done + (running?.let { overlap(it.startTs, n, a, b) } ?: 0)
    }
}

object WidgetRepo {
    private const val PREFS = "widgets"
    private const val KEY = "snapshot"
    private const val KEY_ATTEMPT = "attempt"
    private const val WORK = "widget-refresh"
    /** Periodic updates skip the network when the last try is newer than this. */
    private const val STALE_SEC = 15 * 60

    private val json = Api("", null).json
    private val lock = Mutex()

    @Volatile
    private var flow: MutableStateFlow<WidgetSnapshot>? = null

    fun flow(context: Context): StateFlow<WidgetSnapshot> = state(context)
    fun current(context: Context): WidgetSnapshot = state(context).value

    private fun state(context: Context): MutableStateFlow<WidgetSnapshot> = flow ?: synchronized(this) {
        flow ?: MutableStateFlow(load(context)).also { flow = it }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): WidgetSnapshot {
        val raw = prefs(context).getString(KEY, null) ?: return WidgetSnapshot()
        return runCatching { json.decodeFromString(WidgetSnapshot.serializer(), raw) }.getOrDefault(WidgetSnapshot())
    }

    private fun save(context: Context, s: WidgetSnapshot) {
        state(context).value = s
        prefs(context).edit().putString(KEY, json.encodeToString(WidgetSnapshot.serializer(), s)).apply()
    }

    /** Redraw every widget on the home screen. */
    suspend fun redraw(context: Context) {
        AgendaWidget().updateAll(context)
        TodoWidget().updateAll(context)
        TodayWidget().updateAll(context)
    }

    /** The app's login, with short timeouts. Null when logged out. */
    private fun api(context: Context): Api? {
        val p = context.applicationContext.getSharedPreferences("calories", Context.MODE_PRIVATE)
        val server = p.getString("server", "") ?: ""
        val token = p.getString("token", null)
        return if (server.isBlank() || token == null) null else Api(server, token, readTimeoutSec = 12)
    }

    private fun nowSec() = System.currentTimeMillis() / 1000

    fun isStale(context: Context): Boolean {
        val last = maxOf(current(context).fetchedAt, prefs(context).getLong(KEY_ATTEMPT, 0))
        return nowSec() - last > STALE_SEC
    }

    /** Refresh in the background (waits for a connection). */
    fun requestRefresh(context: Context, onlyIfStale: Boolean = false) {
        if (onlyIfStale && !isStale(context)) return
        val req = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
    }

    /** Fetch everything and redraw. Returns false when the server couldn't be reached. */
    suspend fun refresh(context: Context): Boolean {
        val ok = lock.withLock {
            prefs(context).edit().putLong(KEY_ATTEMPT, nowSec()).apply()
            val api = api(context)
            if (api == null) {
                save(context, WidgetSnapshot(state = "logged_out", fetchedAt = nowSec()))
                return@withLock true
            }
            try {
                save(context, fetch(api))
                true
            } catch (e: ApiException) {
                if (e.status == 401) {
                    save(context, WidgetSnapshot(state = "logged_out", fetchedAt = nowSec()))
                } else {
                    save(context, current(context).copy(error = e.message))
                }
                e.status != 0
            }
        }
        redraw(context)
        return ok
    }

    /** Change the snapshot right away (before the server answers) and redraw. */
    suspend fun edit(context: Context, change: (WidgetSnapshot) -> WidgetSnapshot) {
        save(context, change(current(context)))
        redraw(context)
    }

    /**
     * Run a widget action on the server. The widget already shows the result ([edit]); if the
     * server says no, go back to [before] and show why. Afterwards everything is reloaded.
     */
    suspend fun act(context: Context, before: WidgetSnapshot, block: suspend (Api) -> Unit) {
        val api = api(context)
        if (api == null) {
            refresh(context)
            return
        }
        try {
            block(api)
        } catch (e: ApiException) {
            save(context, before.copy(error = e.message))
            redraw(context)
            if (e.status == 401) refresh(context)
            return
        }
        refresh(context)
    }

    private suspend fun fetch(api: Api): WidgetSnapshot = coroutineScope {
        val today = LocalDate.now()
        val from = dayStart(today)
        val to = dayStart(today.plusDays(7))
        val prefs = async { api.get<WorkPrefs>("/api/prefs") }
        val projects = async { api.get<ProjectsReply>("/api/projects") }
        val tasks = async { api.get<TasksReply>("/api/tasks?done_days=0") }
        val entries = async {
            api.get<EntriesReply>("/api/time/entries?from=${dayStart(today.minusDays(13))}&to=${dayStart(today.plusDays(1))}")
        }
        val plan = async { api.get<PlanReply>("/api/plan?from=$from&to=$to") }
        val day = async { api.get<Day>("/api/day?date=$today") }

        val p = plan.await()
        val events: List<GoogleEvent> = if (p.google) {
            try {
                api.get<GoogleEventsReply>("/api/google/events?from=$from&to=$to").events
            } catch (e: ApiException) {
                emptyList()
            }
        } else {
            emptyList()
        }
        val projectList = projects.await().projects
        val open = tasks.await().tasks.filter { !it.done }
        val e = entries.await()
        val d = day.await()

        val blocks = p.blocks.map { b ->
            val proj = projectList.firstOrNull { it.id == b.projectId }
            val task = open.firstOrNull { it.id == b.taskId }
            val title = b.title.ifBlank { task?.title ?: proj?.name ?: "Planned time" }
            AgendaItem(
                key = "b${b.id}", title = title,
                sub = listOfNotNull(proj?.name, task?.title?.takeIf { it != title }).joinToString(" · "),
                color = proj?.color ?: NO_PROJECT_COLOR, start = b.startTs, end = b.endTs,
                block = true, projectId = b.projectId, taskId = b.taskId, description = b.title,
            )
        }
        val google = events.flatMap { ev -> eventItems(ev, today) }

        WidgetSnapshot(
            state = "ok",
            fetchedAt = nowSec(),
            skew = if (e.now > 0) e.now - nowSec() else 0,
            prefs = prefs.await(),
            projects = projectList,
            tasks = open,
            running = e.running,
            entries = e.entries.filter { it.endTs != null },
            agenda = (blocks + google).sortedWith(compareBy({ it.day ?: "" }, { it.start })),
            day = DaySummary(
                date = d.date,
                kcal = d.totals.kcal, budget = d.targets.kcal + d.exercise,
                protein = d.totals.protein, proteinGoal = d.targets.protein,
                carbs = d.totals.carbs, carbsGoal = d.targets.carbs,
                fat = d.totals.fat, fatGoal = d.targets.fat,
            ),
        )
    }

    private fun eventItems(ev: GoogleEvent, today: LocalDate): List<AgendaItem> {
        val title = ev.title.ifBlank { "(No title)" }
        if (!ev.allDay) {
            val s = ev.startTs ?: return emptyList()
            return listOf(AgendaItem("g${ev.id}", title, ev.calendar, ev.color, s, ev.endTs ?: s))
        }
        val first = runCatching { LocalDate.parse(ev.startDate) }.getOrNull() ?: return emptyList()
        val end = runCatching { LocalDate.parse(ev.endDate) }.getOrNull() ?: first.plusDays(1) // exclusive
        val out = mutableListOf<AgendaItem>()
        var d = maxOf(first, today)
        while (d < end && d < today.plusDays(7)) {
            out += AgendaItem("g${ev.id}-$d", title, ev.calendar, ev.color, day = d.toString())
            d = d.plusDays(1)
        }
        return out
    }
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ok = WidgetRepo.refresh(applicationContext)
        return if (ok || runAttemptCount >= 2) Result.success() else Result.retry()
    }
}
