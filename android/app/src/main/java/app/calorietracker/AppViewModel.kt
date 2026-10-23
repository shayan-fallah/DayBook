package app.calorietracker

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.calorietracker.data.Api
import app.calorietracker.data.ApiException
import app.calorietracker.data.CopyReply
import app.calorietracker.data.Day
import app.calorietracker.data.Entry
import app.calorietracker.data.FavReply
import app.calorietracker.data.Food
import app.calorietracker.data.IdReply
import app.calorietracker.data.Me
import app.calorietracker.data.OkReply
import app.calorietracker.data.Profile
import app.calorietracker.data.QuickLists
import app.calorietracker.data.SearchReply
import app.calorietracker.data.Stats
import app.calorietracker.data.TokenReply
import app.calorietracker.data.Workout
import app.calorietracker.data.WorkoutMeta
import app.calorietracker.data.WorkoutReply
import app.calorietracker.data.WorkoutStats
import app.calorietracker.data.jsonBody
import app.calorietracker.data.normalizeServerUrl
import app.calorietracker.data.urlEncode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("calories", Context.MODE_PRIVATE)
    val api = Api(prefs.getString("server", "") ?: "", prefs.getString("token", null))

    var serverUrl by mutableStateOf(prefs.getString("server", "") ?: "")
        private set
    var username by mutableStateOf(prefs.getString("user", "") ?: "")
        private set
    var loggedIn by mutableStateOf(api.token != null)
        private set

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages
    fun toast(msg: String) {
        _messages.tryEmit(msg)
    }

    // Today
    var date by mutableStateOf(LocalDate.now())
        private set
    private var followToday = true
    var day by mutableStateOf<Day?>(null)
        private set
    var refreshing by mutableStateOf(false)
        private set

    // Food lists, history, profile
    var quick by mutableStateOf<QuickLists?>(null)
        private set
    var statsRange by mutableIntStateOf(30)
        private set
    var stats by mutableStateOf<Stats?>(null)
        private set
    var profile by mutableStateOf<Profile?>(null)
        private set

    // Gym
    var workoutMeta by mutableStateOf<WorkoutMeta?>(null)
        private set
    var gymRange by mutableIntStateOf(30)
        private set
    var gym by mutableStateOf<WorkoutStats?>(null)
        private set

    init {
        // The day itself loads in onResume(), which runs when the screen first appears.
        if (loggedIn) loadQuick()
    }

    // ------------------------------------------------------------ errors

    private fun handle(e: ApiException) {
        if (e.status == 401 && loggedIn) {
            clearSession()
            toast("Your session ended. Please log in again.")
        } else {
            toast(e.message ?: "Something went wrong")
        }
    }

    private fun launchSafe(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (e: ApiException) {
            handle(e)
        }
    }

    /** Run a server action; errors are shown as a message. Returns true on success. */
    private suspend fun act(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: ApiException) {
        handle(e)
        false
    }

    // ------------------------------------------------------------ account

    /** Check an address before logging in. Throws ApiException with a readable message. */
    suspend fun checkServer(url: String): Me {
        val probe = Api(normalizeServerUrl(url), null)
        return probe.get("/api/me")
    }

    /** Log in or sign up. Throws ApiException so the login screen can show the error inline. */
    suspend fun login(url: String, user: String, password: String, signup: Boolean) {
        val base = normalizeServerUrl(url)
        val probe = Api(base, null)
        val reply: TokenReply = probe.post(
            if (signup) "/api/signup" else "/api/login",
            jsonBody("username" to user.trim(), "password" to password, "want_token" to true),
        )
        val token = reply.token ?: throw ApiException(0, "This server is too old for the app. Update app.py on the server.")
        prefs.edit().putString("server", base).putString("token", token).putString("user", user.trim()).apply()
        api.baseUrl = base
        api.token = token
        serverUrl = base
        username = user.trim()
        date = LocalDate.now()
        followToday = true
        loggedIn = true
        loadDay()
        loadQuick()
    }

    fun logout() {
        viewModelScope.launch {
            runCatching { api.post<OkReply>("/api/logout") }
            clearSession()
        }
    }

    private fun clearSession() {
        prefs.edit().remove("token").apply()
        api.token = null
        loggedIn = false
        day = null
        quick = null
        stats = null
        profile = null
        workoutMeta = null
        gym = null
    }

    suspend fun changePassword(current: String, new: String): Boolean = act {
        api.post<OkReply>("/api/password", jsonBody("current" to current, "new" to new))
        toast("Password changed")
    }

    // ------------------------------------------------------------ day

    fun loadDay(spinner: Boolean = false) = launchSafe {
        val d = date
        if (spinner) refreshing = true
        try {
            val result: Day = api.get("/api/day?date=$d")
            if (d == date) day = result
        } finally {
            refreshing = false
        }
    }

    fun changeDate(d: LocalDate) {
        if (d == date) return
        date = d
        followToday = d == LocalDate.now()
        day = null
        loadDay()
    }

    /** Called when the app comes back to the foreground. */
    fun onResume() {
        if (!loggedIn) return
        val today = LocalDate.now()
        if (followToday && date != today) {
            date = today
            day = null
        }
        loadDay()
    }

    suspend fun addFood(foodId: Long, unit: String, qty: Double, meal: String): Boolean = act {
        api.post<IdReply>(
            "/api/entries",
            jsonBody("date" to date.toString(), "meal" to meal, "food_id" to foodId, "unit" to unit, "qty" to qty),
        )
        loadDay()
        loadQuick()
    }

    suspend fun addQuick(name: String, kcal: Double, protein: Double, carbs: Double, fat: Double, meal: String) = act {
        api.post<IdReply>(
            "/api/entries",
            jsonBody(
                "date" to date.toString(), "meal" to meal, "name" to name.ifBlank { "Quick add" },
                "kcal" to kcal, "protein" to protein, "carbs" to carbs, "fat" to fat,
            ),
        )
        loadDay()
    }

    suspend fun editEntry(entry: Entry, body: JsonObject): Boolean = act {
        api.patch<OkReply>("/api/entries/${entry.id}", body)
        loadDay()
    }

    suspend fun deleteEntry(entry: Entry): Boolean = act {
        api.delete<OkReply>("/api/entries/${entry.id}")
        loadDay()
        toast("Deleted ${entry.name}")
    }

    fun copyFromYesterday(meal: String?) = launchSafe {
        val reply: CopyReply = api.post(
            "/api/entries/copy",
            jsonBody("from" to date.minusDays(1).toString(), "to" to date.toString(), "meal" to meal),
        )
        toast(
            when (reply.copied) {
                0 -> "Nothing was logged then"
                1 -> "Copied 1 item"
                else -> "Copied ${reply.copied} items"
            },
        )
        if (reply.copied > 0) loadDay()
    }

    fun setWater(glasses: Int) {
        val d = day ?: return
        val n = glasses.coerceIn(0, 50)
        day = d.copy(water = n)
        launchSafe { api.post<OkReply>("/api/days", jsonBody("date" to d.date, "water" to n)) }
    }

    suspend fun saveWeight(kg: Double?): Boolean = act {
        val d = date.toString()
        if (kg == null) api.delete<OkReply>("/api/weights/$d")
        else api.post<OkReply>("/api/weights", jsonBody("date" to d, "kg" to kg))
        loadDay()
        toast(if (kg == null) "Weight removed" else "Weight saved")
    }

    // ------------------------------------------------------------ workouts

    fun loadWorkoutMeta() = launchSafe { workoutMeta = api.get("/api/workouts/meta") }

    /** The activity list for the workout form; loads it the first time. */
    suspend fun ensureWorkoutMeta(): WorkoutMeta? = workoutMeta ?: try {
        api.get<WorkoutMeta>("/api/workouts/meta").also { workoutMeta = it }
    } catch (e: ApiException) {
        handle(e)
        null
    }

    fun loadGym(range: Int = gymRange) = launchSafe {
        gymRange = range
        val result: WorkoutStats = api.get("/api/workouts/stats?end=${LocalDate.now()}&days=$range")
        if (range == gymRange) gym = result
    }

    /** Add (id = null) or update a workout. Returns the saved calories, or null on error. */
    suspend fun saveWorkout(id: Long?, body: JsonObject): Int? = try {
        val reply: WorkoutReply = if (id == null) api.post("/api/workouts", body) else api.patch("/api/workouts/$id", body)
        afterWorkoutChange()
        reply.kcal
    } catch (e: ApiException) {
        handle(e)
        null
    }

    suspend fun deleteWorkout(w: Workout): Boolean = act {
        api.delete<OkReply>("/api/workouts/${w.id}")
        afterWorkoutChange()
        toast("Workout deleted")
    }

    private fun afterWorkoutChange() {
        loadDay()
        loadWorkoutMeta()
        if (gym != null) loadGym()
    }

    // ------------------------------------------------------------ foods

    suspend fun search(query: String): List<Food>? = try {
        api.get<SearchReply>("/api/foods/search?q=${urlEncode(query)}").results
    } catch (e: ApiException) {
        handle(e)
        null
    }

    fun loadQuick() = launchSafe { quick = api.get("/api/foods/quick") }

    suspend fun getFood(id: Long): Food? = try {
        api.get<Food>("/api/foods/$id")
    } catch (e: ApiException) {
        handle(e)
        null
    }

    /** Returns the new favorite state, or null on error. */
    suspend fun toggleFavorite(food: Food): Boolean? = try {
        val reply: FavReply = if (food.fav) api.delete("/api/favorites/${food.id}") else api.post("/api/favorites/${food.id}")
        loadQuick()
        reply.fav
    } catch (e: ApiException) {
        handle(e)
        null
    }

    suspend fun saveCustomFood(id: Long?, body: JsonObject): Boolean = act {
        if (id == null) api.post<IdReply>("/api/foods", body) else api.put<IdReply>("/api/foods/$id", body)
        loadQuick()
        toast(if (id == null) "Food created" else "Food updated")
    }

    suspend fun deleteCustomFood(id: Long): Boolean = act {
        api.delete<OkReply>("/api/foods/$id")
        loadQuick()
        toast("Food deleted")
    }

    // ------------------------------------------------------------ history & profile

    fun loadStats(range: Int = statsRange) = launchSafe {
        statsRange = range
        val result: Stats = api.get("/api/stats?end=${LocalDate.now()}&days=$range")
        if (range == statsRange) stats = result
    }

    suspend fun deleteWeight(date: String): Boolean = act {
        api.delete<OkReply>("/api/weights/$date")
        loadStats()
        loadDay()
    }

    fun loadProfile() = launchSafe { profile = api.get("/api/profile") }

    suspend fun saveProfile(body: JsonObject): Boolean = act {
        val p: Profile = api.put("/api/profile", body)
        profile = p
        loadDay()
        if (gym != null) loadGym()
        toast("Saved. Daily goal: ${app.calorietracker.ui.fmt(p.targets.kcal)} kcal")
    }
}
