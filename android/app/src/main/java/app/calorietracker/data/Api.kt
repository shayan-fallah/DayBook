package app.calorietracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

// ---------------------------------------------------------------- models (mirror app.py responses)

@Serializable
data class Me(val signupAllowed: Boolean = false, val firstUser: Boolean = false, val user: String? = null)

@Serializable
data class TokenReply(val ok: Boolean = false, val token: String? = null)

@Serializable
data class Nutrients(
    val kcal: Double = 0.0, val protein: Double = 0.0, val carbs: Double = 0.0, val fat: Double = 0.0,
    val fiber: Double = 0.0, val sugar: Double = 0.0, val sodium: Double = 0.0,
)

@Serializable
data class Targets(
    val kcal: Double = 2000.0, val protein: Double = 0.0, val carbs: Double = 0.0, val fat: Double = 0.0,
    val fiber: Double = 0.0, val sugar: Double = 50.0, val sodium: Double = 2300.0, val water: Int = 8,
    val bmr: Double? = null, val tdee: Double? = null, val suggested: Double? = null, val manual: Boolean = false,
)

@Serializable
data class Entry(
    val id: Long, val date: String, val meal: String, val foodId: Long? = null, val name: String,
    val unit: String? = null, val qty: Double? = null, val grams: Double? = null,
    val kcal: Double = 0.0, val protein: Double = 0.0, val carbs: Double = 0.0, val fat: Double = 0.0,
    val fiber: Double = 0.0, val sugar: Double = 0.0, val sodium: Double = 0.0,
)

@Serializable
data class Day(
    val date: String, val entries: List<Entry> = emptyList(), val totals: Nutrients = Nutrients(),
    val targets: Targets = Targets(), val water: Int = 0,
    /** Workout calories added to today's goal (all, half or none of [burned], see Settings). */
    val exercise: Int = 0, val burned: Int = 0, val exercisePct: Int = 100,
    val workouts: List<Workout> = emptyList(),
    val weight: Double? = null, val lastWeight: Double? = null,
)

@Serializable
data class Workout(
    val id: Long, val date: String, val activity: String, val name: String, val kind: String = "other",
    val minutes: Int? = null, val intensity: String = "moderate", val muscles: List<String> = emptyList(),
    val distanceKm: Double? = null, val kcal: Int = 0, val kcalManual: Boolean = false, val notes: String = "",
)

@Serializable
data class ActivityInfo(
    val key: String, val name: String, val kind: String, val met: List<Double> = emptyList(), val distance: Boolean = false,
)

@Serializable
data class NamedKey(val key: String, val name: String)

@Serializable
data class WorkoutMeta(
    val activities: List<ActivityInfo> = emptyList(), val intensities: List<NamedKey> = emptyList(),
    val muscles: List<NamedKey> = emptyList(), val weight: Double? = null, val defaultWeight: Double = 70.0,
    val recent: List<Workout> = emptyList(),
)

@Serializable
data class WorkoutReply(val id: Long = 0, val kcal: Int = 0)

@Serializable
data class TrainingBucket(
    val start: String, val end: String, val strength: Int = 0, val cardio: Int = 0, val other: Int = 0,
    val sessions: Int = 0, val kcal: Int = 0,
) {
    val minutes get() = strength + cardio + other
}

@Serializable
data class TrainingTotals(
    val sessions: Int = 0, val minutes: Int = 0, val kcal: Int = 0, val days: Int = 0,
    val strength: Int = 0, val cardio: Int = 0, val other: Int = 0,
)

@Serializable
data class TrainingWeek(val days: Int = 0, val sessions: Int = 0, val goal: Int = 4, val minutes: Int = 0, val kcal: Int = 0)

@Serializable
data class ActivityTotal(val activity: String, val name: String, val kind: String, val sessions: Int = 0, val minutes: Int = 0, val kcal: Int = 0)

@Serializable
data class MuscleStat(val key: String, val name: String, val last: String? = null, val daysAgo: Int? = null, val sessions: Int = 0)

@Serializable
data class WorkoutStats(
    val start: String, val end: String, val bucketDays: Int = 1, val buckets: List<TrainingBucket> = emptyList(),
    val totals: TrainingTotals = TrainingTotals(), val week: TrainingWeek = TrainingWeek(), val goalStreak: Int = 0,
    val byActivity: List<ActivityTotal> = emptyList(), val muscles: List<MuscleStat> = emptyList(),
    val workouts: List<Workout> = emptyList(),
)

@Serializable
data class FoodUnit(val name: String, val grams: Double)

@Serializable
data class LastUse(val unit: String, val qty: Double, val kcal: Double? = null)

@Serializable
data class Food(
    val id: Long, val name: String,
    val kcal: Double = 0.0, val protein: Double = 0.0, val carbs: Double = 0.0, val fat: Double = 0.0,
    val fiber: Double = 0.0, val sugar: Double = 0.0, val sodium: Double = 0.0,
    val custom: Boolean = false, val source: String = "", val fav: Boolean = false,
    val unit1: FoodUnit? = null, val units: List<FoodUnit> = emptyList(), val last: LastUse? = null, val uses: Int = 0,
    /** A database food this user changed; [original] holds the database's values. */
    val edited: Boolean = false, val original: FoodOriginal? = null,
)

@Serializable
data class FoodOriginal(val name: String = "", val kcal: Double = 0.0)

@Serializable
data class FoodSaved(val id: Long = 0, val updatedEntries: Int = 0)

@Serializable
data class QuickLists(
    val recent: List<Food> = emptyList(), val favorites: List<Food> = emptyList(), val mine: List<Food> = emptyList(),
    val edited: List<Food> = emptyList(),
)

@Serializable
data class SearchReply(val results: List<Food> = emptyList())

@Serializable
data class DayStat(
    val date: String, val budget: Double = 0.0, val exercise: Double = 0.0,
    val kcal: Double? = null, val protein: Double? = null, val carbs: Double? = null, val fat: Double? = null,
)

@Serializable
data class WeightPoint(val date: String, val kg: Double)

@Serializable
data class TopFood(val name: String, val kcal: Double = 0.0, val times: Int = 0)

@Serializable
data class Averages(val kcal: Double? = null, val protein: Double? = null, val carbs: Double? = null, val fat: Double? = null)

@Serializable
data class Stats(
    val start: String, val end: String, val days: List<DayStat> = emptyList(), val targets: Targets = Targets(),
    val loggedDays: Int = 0, val onTarget: Int = 0, val streak: Int = 0, val avg: Averages = Averages(),
    val weights: List<WeightPoint> = emptyList(), val currentWeight: Double? = null,
    val targetWeight: Double? = null, val rateKgWeek: Double? = null,
    val topFoods: List<TopFood> = emptyList(), val meals: Map<String, Double> = emptyMap(),
)

@Serializable
data class Profile(
    val sex: String? = null, val age: Int? = null, val heightCm: Double? = null, val activity: String? = "light",
    val rateKgWeek: Double? = 0.0, val targetWeight: Double? = null, val calorieGoal: Int? = null,
    val proteinPct: Int = 25, val carbsPct: Int = 45, val fatPct: Int = 30, val waterGoal: Int = 8,
    val exercisePct: Int = 100, val workoutGoal: Int = 4,
    val weight: Double? = null, val targets: Targets = Targets(),
)

// ---------------------------------------------------------------- time tracking, to-dos, planner

@Serializable
data class WorkPrefs(val workGoal: Double = 8.0, val weekStart: String = "auto", val calendar: String = "gregorian")

@Serializable
data class Project(
    val id: Long, val name: String, val color: String = "#2a78d6", val archived: Boolean = false,
    /** Seconds logged in total. */
    val total: Long = 0, val openTasks: Int = 0,
)

@Serializable
data class ProjectsReply(val projects: List<Project> = emptyList(), val unassigned: Long = 0)

@Serializable
data class DeleteProjectReply(val ok: Boolean = true, val deletedTasks: Int = 0)

@Serializable
data class TaskItem(
    val id: Long, val projectId: Long? = null, val title: String, val notes: String = "", val due: String? = null,
    val priority: Int = 0, val done: Boolean = false, val doneAt: Long? = null, val createdAt: Long = 0,
    val total: Long = 0,
    /** When Telegram sends an alarm for it (epoch seconds), or null. */
    val remindAt: Long? = null,
)

@Serializable
data class TasksReply(val tasks: List<TaskItem> = emptyList())

@Serializable
data class TimeEntry(
    val id: Long, val projectId: Long? = null, val taskId: Long? = null, val description: String = "",
    val startTs: Long, val endTs: Long? = null,
)

@Serializable
data class EntriesReply(val entries: List<TimeEntry> = emptyList(), val running: TimeEntry? = null, val now: Long = 0)

@Serializable
data class TimerReply(val running: TimeEntry? = null, val now: Long = 0)

@Serializable
data class StopReply(val stopped: TimeEntry? = null)

@Serializable
data class PlanBlock(
    val id: Long, val title: String = "", val notes: String = "", val projectId: Long? = null, val taskId: Long? = null,
    val startTs: Long, val endTs: Long, val series: String? = null,
    /** "synced", "pending" or "error" when Google Calendar is connected. */
    val gcal: String? = null, val gcalError: String? = null,
)

@Serializable
data class PlanReply(val blocks: List<PlanBlock> = emptyList(), val google: Boolean = false)

@Serializable
data class PlanAddReply(val ids: List<Long> = emptyList())

@Serializable
data class DeleteCountReply(val ok: Boolean = true, val deleted: Int = 0)

@Serializable
data class GoogleStatus(
    val available: Boolean = false, val connected: Boolean = false, val redirectUri: String = "",
    val email: String? = null, val calendarId: String? = null, val showEvents: Boolean = true,
    val lastSync: Long? = null, val lastError: String? = null, val needsReauth: Boolean = false, val pending: Int = 0,
    val pushed: Int = 0,
)

@Serializable
data class TelegramSettings(
    val brief: Boolean = true, val briefTime: String = "08:00", val lead: Int = 5, val timerHours: Int = 4,
    val ringAgain: Boolean = true,
)

@Serializable
data class TelegramProxy(val host: String = "", val port: Int? = null, val username: String = "", val hasPassword: Boolean = false)

@Serializable
data class TelegramStatus(
    val configured: Boolean = false, val linked: Boolean = false, val bot: String = "", val tokenHint: String = "",
    val chat: String? = null, val linkCode: String? = null, val linkUrl: String? = null,
    val proxy: TelegramProxy? = null, val settings: TelegramSettings = TelegramSettings(), val tz: String = "UTC",
    val lastOk: Long? = null, val lastError: String? = null, val lastErrorAt: Long? = null,
)

@Serializable
data class GoogleCalendar(
    val id: String, val name: String, val color: String = "#888888", val primary: Boolean = false,
    val shown: Boolean = false, val writable: Boolean = false,
)

@Serializable
data class CalendarsReply(val calendars: List<GoogleCalendar> = emptyList())

@Serializable
data class GoogleEvent(
    val id: String? = null, val title: String = "", val calendar: String = "", val color: String = "#888888",
    val link: String? = null, val allDay: Boolean = false, val startTs: Long? = null, val endTs: Long? = null,
    val startDate: String? = null, val endDate: String? = null,
)

@Serializable
data class GoogleEventsReply(
    val connected: Boolean = false, val events: List<GoogleEvent> = emptyList(), val changed: Int = 0,
    val error: String? = null,
)

@Serializable
data class UrlReply(val url: String = "")

@Serializable
data class IdReply(val id: Long = 0)

@Serializable
data class CopyReply(val copied: Int = 0)

@Serializable
data class FavReply(val fav: Boolean = false)

@Serializable
data class OkReply(val ok: Boolean = true)

// ---------------------------------------------------------------- client

class ApiException(val status: Int, message: String) : Exception(message)

/** Build a JSON object from Kotlin values (numbers, strings, booleans, null). */
fun jsonBody(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to v.toJson() })

private fun Any?.toJson(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Collection<*> -> JsonArray(map { it.toJson() })
    else -> JsonPrimitive(toString())
}

fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")

/** Turn what the user typed ("1.2.3.4:8080", "calories.example.com/") into a base URL. */
fun normalizeServerUrl(input: String): String {
    var s = input.trim().trimEnd('/')
    if (s.isEmpty()) return s
    if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) {
        val host = s.substringBefore('/').substringBefore(':')
        val looksLocal = host.all { it.isDigit() || it == '.' } || host == "localhost" || s.contains(':')
        s = (if (looksLocal) "http://" else "https://") + s
    }
    return s
}

/** [readTimeoutSec] is shorter for the home-screen widgets, which only get a limited time to run. */
class Api(var baseUrl: String, var token: String?, readTimeoutSec: Long = 25) {
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    suspend fun <T> call(method: String, path: String, body: JsonObject?, strategy: DeserializationStrategy<T>): T =
        withContext(Dispatchers.IO) {
            val reqBody = when {
                body != null -> body.toString().toRequestBody(jsonType)
                method == "GET" || method == "DELETE" -> null
                else -> "{}".toRequestBody(jsonType)
            }
            val request = Request.Builder()
                .url(baseUrl + path)
                .method(method, reqBody)
                // The server requires this on writes without a token (login, sign-up). See app.py dispatch().
                .header("X-Requested-With", "fetch")
                .apply { token?.let { header("Authorization", "Bearer $it") } }
                .build()
            val response = try {
                http.newCall(request).execute()
            } catch (e: IOException) {
                throw ApiException(0, "Can't reach the server. Check the address and your internet connection.")
            } catch (e: IllegalArgumentException) {
                throw ApiException(0, "That server address doesn't look right.")
            }
            response.use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    val msg = runCatching {
                        json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
                    }.getOrNull()
                    throw ApiException(res.code, msg ?: "Server error (${res.code})")
                }
                try {
                    json.decodeFromString(strategy, text)
                } catch (e: Exception) {
                    throw ApiException(res.code, "Unexpected reply from server. Is this your Daybook address?")
                }
            }
        }

    suspend inline fun <reified T> get(path: String): T = call("GET", path, null, serializer<T>())
    suspend inline fun <reified T> post(path: String, body: JsonObject? = null): T = call("POST", path, body, serializer<T>())
    suspend inline fun <reified T> put(path: String, body: JsonObject): T = call("PUT", path, body, serializer<T>())
    suspend inline fun <reified T> patch(path: String, body: JsonObject): T = call("PATCH", path, body, serializer<T>())
    suspend inline fun <reified T> delete(path: String): T = call("DELETE", path, null, serializer<T>())
}
