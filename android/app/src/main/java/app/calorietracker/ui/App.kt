package app.calorietracker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import app.calorietracker.AppViewModel
import app.calorietracker.data.Food
import kotlinx.coroutines.launch

enum class Tab(val label: String, val icon: ImageVector) {
    Today("Today", Icons.Filled.Home),
    Gym("Gym", DumbbellIcon),
    History("History", Icons.Filled.DateRange),
    Foods("Foods", Icons.AutoMirrored.Filled.List),
    Settings("Settings", Icons.Filled.Settings),
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
    var addMeal by rememberSaveable { mutableStateOf<String?>(null) }
    // A food picked outside the add screen (e.g. from Favorites) opens the portion sheet directly.
    var quickFood by remember { mutableStateOf<Food?>(null) }

    val meal = addMeal
    if (meal != null) {
        BackHandler { addMeal = null }
        AddFoodScreen(vm, initialMeal = meal, snackbar = snackbar, onClose = { addMeal = null })
        return
    }

    BackHandler(enabled = tab != Tab.Today) { tab = Tab.Today }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
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
                Tab.History -> HistoryScreen(vm)
                Tab.Foods -> FoodsScreen(vm, onPick = { quickFood = it })
                Tab.Settings -> SettingsScreen(vm)
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
