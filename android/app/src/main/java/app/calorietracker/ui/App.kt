package app.calorietracker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import app.calorietracker.AppViewModel
import app.calorietracker.Nav
import app.calorietracker.data.Food
import kotlinx.coroutines.launch
import java.time.LocalDate

enum class Tab(val label: String, val icon: ImageVector) {
    Today("Today", FoodIcon),
    Gym("Gym", DumbbellIcon),
    Time("Time", TimerIcon),
    Plan("Plan", PlanIcon),
    More("More", MoreIcon),
}

/** Pages reached from the More tab. */
enum class MorePage(val title: String, val sub: String) {
    History("History", "Calories, weight and macros over time"),
    Foods("Foods", "Find and edit foods, your own foods, favorites"),
    Settings("Settings", "Goals, Google Calendar, password"),
}

@Composable
fun CalorieApp(vm: AppViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        vm.messages.collect { msg ->
            snackbar.currentSnackbarData?.dismiss()
            scope.launch { snackbar.showSnackbar(msg) }
        }
    }
    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { }
    }

    if (!vm.loggedIn) {
        LoginScreen(vm, snackbar)
        return
    }

    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    var morePage by rememberSaveable { mutableStateOf<MorePage?>(null) }
    var addMeal by rememberSaveable { mutableStateOf<String?>(null) }
    // A food picked outside the add screen (e.g. from Favorites) opens the portion sheet directly.
    var quickFood by remember { mutableStateOf<Food?>(null) }
    val work = vm.work

    // A tap on a home-screen widget: open the matching screen.
    val navTarget by Nav.target.collectAsState()
    LaunchedEffect(navTarget) {
        val target = navTarget ?: return@LaunchedEffect
        Nav.consume()
        morePage = null
        addMeal = null
        when (target) {
            "add" -> {
                tab = Tab.Today
                addMeal = mealForNow()
            }
            "timer" -> {
                tab = Tab.Time
                work.timeTab = "timer"
            }
            "plan" -> {
                tab = Tab.Plan
                work.planTab = "schedule"
                work.changePlanDate(LocalDate.now())
            }
            "todo", "newtask" -> {
                tab = Tab.Plan
                work.planTab = "todo"
                if (target == "newtask") work.newTaskRequest = true
            }
            else -> tab = Tab.Today
        }
    }

    val meal = addMeal
    if (meal != null) {
        BackHandler { addMeal = null }
        AddFoodScreen(vm, initialMeal = meal, snackbar = snackbar, onClose = { addMeal = null })
        return
    }

    BackHandler(enabled = tab != Tab.Today || morePage != null) {
        if (morePage != null) morePage = null else tab = Tab.Today
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                val running = work.running
                if (running != null && !(tab == Tab.Time && work.timeTab == "timer")) {
                    TimerBar(work, running) {
                        tab = Tab.Time
                        work.timeTab = "timer"
                    }
                }
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = {
                                if (t == Tab.More && tab == Tab.More) morePage = null
                                tab = t
                            },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            when (tab) {
                Tab.Today -> TodayScreen(vm, onAdd = { addMeal = it })
                Tab.Gym -> GymScreen(vm)
                Tab.Time -> TimeScreen(work)
                Tab.Plan -> PlanScreen(work)
                Tab.More -> {
                    val page = morePage
                    if (page == null) MoreScreen { morePage = it } else Column(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { morePage = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                            Text(page.title, style = MaterialTheme.typography.titleLarge)
                        }
                        Box(Modifier.weight(1f)) {
                            when (page) {
                                MorePage.History -> HistoryScreen(vm)
                                MorePage.Foods -> FoodsScreen(vm, onPick = { quickFood = it })
                                MorePage.Settings -> SettingsScreen(vm)
                            }
                        }
                    }
                }
            }
        }
    }

    quickFood?.let { food ->
        PortionSheet(
            vm = vm,
            foodId = food.id,
            entry = null,
            initialMeal = mealForNow(),
            onDismiss = { quickFood = null },
            onDone = { quickFood = null },
        )
    }
}

@Composable
private fun MoreScreen(onOpen: (MorePage) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        AppCard(padding = 0.dp) {
            MorePage.entries.forEachIndexed { i, page ->
                if (i > 0) HorizontalDivider(color = p.grid)
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(page) }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(page.title, style = MaterialTheme.typography.titleMedium)
                        SubText(page.sub, color = p.muted)
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = p.muted)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
