package app.calorietracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.calorietracker.AppViewModel
import app.calorietracker.data.Entry
import app.calorietracker.data.Food
import app.calorietracker.data.FoodUnit
import app.calorietracker.data.LastUse
import app.calorietracker.data.jsonBody
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TABS = listOf("recent" to "Recent", "favorites" to "Favorites", "mine" to "My foods", "quick" to "Quick calories")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFoodScreen(vm: AppViewModel, initialMeal: String, snackbar: SnackbarHostState, onClose: () -> Unit) {
    var meal by rememberSaveable { mutableStateOf(initialMeal) }
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf("recent") }
    var results by remember { mutableStateOf<List<Food>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.loadQuick() }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            results = null
            return@LaunchedEffect
        }
        delay(250) // wait for the user to stop typing
        searching = true
        try {
            results = vm.search(q) ?: emptyList()
        } finally {
            searching = false
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Add to ${mealLabel(meal).lowercase()}") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(horizontal = 16.dp),
        ) {
            MealSelector(meal, { meal = it })
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search foods: نان، برنج، سیب…") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Clear") }
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { results?.firstOrNull()?.let { picked = it.id } }),
                shape = RoundedCornerShape(12.dp),
            )
            if (query.isBlank()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TABS.forEach { (key, label) ->
                        FilterChip(selected = tab == key, onClick = { tab = key }, label = { Text(label) })
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }

            val quick = vm.quick
            when {
                query.isNotBlank() -> {
                    val list = results
                    if (list == null || (searching && list.isEmpty())) {
                        Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
                    } else if (list.isEmpty()) {
                        EmptyNote("No matches. Try fewer or different words, or use Quick calories.")
                    } else {
                        FoodList(list, onPick = { picked = it.id }, onReAdd = null)
                    }
                }
                tab == "quick" -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    QuickForm(vm, entry = null, meal = meal, onSaved = {}, onDelete = null)
                }
                quick == null -> Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
                else -> {
                    val list = when (tab) {
                        "favorites" -> quick.favorites
                        "mine" -> quick.mine
                        else -> quick.recent
                    }
                    if (list.isEmpty()) {
                        EmptyNote(
                            when (tab) {
                                "favorites" -> "Tap ♡ on a food to add it to your favorites."
                                "mine" -> "Create your own foods on the Foods tab."
                                else -> "Foods you log show up here for one-tap adding."
                            },
                        )
                    } else {
                        FoodList(
                            list,
                            onPick = { picked = it.id },
                            onReAdd = if (tab == "recent") { food ->
                                val last = food.last
                                if (last != null) scope.launch {
                                    if (vm.addFood(food.id, last.unit, last.qty, meal)) vm.toast("Added to ${mealLabel(meal).lowercase()}: ${food.name}")
                                }
                            } else null,
                        )
                    }
                }
            }
        }
    }

    picked?.let { id ->
        PortionSheet(vm, foodId = id, entry = null, initialMeal = meal, showMeal = false,
            onDismiss = { picked = null }, onDone = { picked = null })
    }
}

@Composable
private fun FoodList(list: List<Food>, onPick: (Food) -> Unit, onReAdd: ((Food) -> Unit)?) {
    val p = LocalPalette.current
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(list, key = { it.id }) { food ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(food) }.padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (food.fav) Text("♥ ", color = p.star, fontSize = 13.sp)
                        FoodName(food.name, Modifier.weight(1f, fill = false))
                        if (food.custom) {
                            Spacer(Modifier.width(6.dp))
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(6.dp)) {
                                Text("mine", fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp))
                            }
                        }
                    }
                    MixedLine(foodSub(food))
                }
                if (onReAdd != null && food.last != null) {
                    OutlinedButton(onClick = { onReAdd(food) }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Text("+ Add")
                    }
                }
            }
            HorizontalDivider(color = p.grid)
        }
    }
}

// ---------------------------------------------------------------- portion sheet

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PortionSheet(
    vm: AppViewModel,
    foodId: Long,
    entry: Entry?,
    initialMeal: String,
    showMeal: Boolean = true,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val p = LocalPalette.current
    var food by remember(foodId) { mutableStateOf<Food?>(null) }
    var units by remember(foodId) { mutableStateOf(listOf<FoodUnit>()) }
    var unitName by remember(foodId) { mutableStateOf("") }
    var qtyText by remember(foodId) { mutableStateOf("") }
    var meal by remember { mutableStateOf(initialMeal) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(foodId) {
        val f = vm.getFood(foodId)
        if (f == null) {
            onDismiss()
            return@LaunchedEffect
        }
        val list = f.units.toMutableList()
        val eu = entry?.unit
        if (entry != null && eu != null && list.none { it.name == eu }) {
            val q = entry.qty ?: 0.0
            list.add(0, FoodUnit(eu, if (q > 0) (entry.grams ?: 0.0) / q else 1.0))
        }
        if (list.isEmpty()) list.add(FoodUnit(GRAM, 1.0))
        val pref = if (entry != null && eu != null) LastUse(eu, entry.qty ?: 1.0) else f.last
        if (pref != null && list.any { it.name == pref.unit }) {
            unitName = pref.unit
            qtyText = pref.qty.trimmed()
        } else {
            unitName = list.first().name
            qtyText = if (unitName == GRAM) "100" else "1"
        }
        units = list
        food = f
    }

    fun close(after: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { after() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val f = food
        if (f == null) {
            Box(Modifier.fillMaxWidth().height(220.dp), Alignment.Center) { CircularProgressIndicator() }
            return@ModalBottomSheet
        }
        val unit = units.firstOrNull { it.name == unitName } ?: units.first()
        val qty = parseNum(qtyText)
        val valid = qty != null && qty > 0
        val factor = if (valid) qty!! * unit.grams / 100.0 else 0.0

        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                FoodName(f.name, Modifier.weight(1f).padding(top = 8.dp), style = MaterialTheme.typography.titleLarge, maxLines = 3)
                IconButton(onClick = {
                    scope.launch { vm.toggleFavorite(f)?.let { food = f.copy(fav = it) } }
                }) {
                    if (f.fav) Icon(Icons.Filled.Favorite, "Remove from favorites", tint = p.star)
                    else Icon(Icons.Filled.FavoriteBorder, "Add to favorites", tint = p.muted)
                }
            }
            SubText("Per 100 g: ${fmt(f.kcal)} kcal · protein ${fmt1(f.protein)} g · carbs ${fmt1(f.carbs)} g · fat ${fmt1(f.fat)} g")

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                NumberField(
                    value = qtyText,
                    onValueChange = { qtyText = it },
                    label = "Amount",
                    modifier = Modifier.weight(0.38f),
                    imeAction = ImeAction.Done,
                )
                UnitDropdown(units, unitName, Modifier.weight(0.62f)) { newName ->
                    val old = unit
                    val q = parseNum(qtyText)
                    if (q != null && q > 0) {
                        if (newName == GRAM && old.name != GRAM) qtyText = Math.round(q * old.grams).toString()
                        else if (old.name == GRAM && newName != GRAM) qtyText = "1"
                    }
                    unitName = newName
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val chips = if (unitName == GRAM) listOf("50" to "50 g", "100" to "100 g", "150" to "150 g", "200" to "200 g", "300" to "300 g")
                else listOf("0.5" to "½", "1" to "1", "1.5" to "1½", "2" to "2", "3" to "3")
                chips.forEach { (v, label) -> SuggestionChip(onClick = { qtyText = v }, label = { Text(label) }) }
            }

            Surface(color = p.surface2, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(if (valid) fmt(f.kcal * factor) else "–", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text(" kcal", color = p.ink2, modifier = Modifier.padding(bottom = 4.dp))
                        if (valid && unit.name != GRAM) {
                            Text(" · ${fmt1(qty!! * unit.grams)} g", color = p.muted, modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        NutrientChip("Protein", f.protein * factor, p.protein, valid)
                        NutrientChip("Carbs", f.carbs * factor, p.carbs, valid)
                        NutrientChip("Fat", f.fat * factor, p.fat, valid)
                        NutrientChip("Fiber", f.fiber * factor, null, valid)
                        NutrientChip("Sugar", f.sugar * factor, null, valid)
                    }
                }
            }

            if (showMeal || entry != null) MealSelector(meal, { meal = it })

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (entry != null) {
                    OutlinedButton(
                        onClick = { scope.launch { if (vm.deleteEntry(entry)) close(onDone) } },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete") }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    enabled = valid && !busy,
                    modifier = Modifier.width(140.dp).height(48.dp),
                    onClick = {
                        val amount = qty ?: return@Button
                        busy = true
                        scope.launch {
                            val ok = if (entry != null) {
                                vm.editEntry(entry, jsonBody("qty" to amount, "unit" to unitName, "meal" to meal))
                            } else {
                                vm.addFood(f.id, unitName, amount, meal)
                            }
                            busy = false
                            if (ok) {
                                vm.toast(if (entry != null) "Saved" else "Added to ${mealLabel(meal).lowercase()}: ${f.name}")
                                close(onDone)
                            }
                        }
                    },
                ) { Text(if (entry != null) "Save" else "Add") }
            }
        }
    }
}

@Composable
private fun NutrientChip(label: String, value: Double, color: androidx.compose.ui.graphics.Color?, valid: Boolean) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (color != null) {
            Swatch(color)
            Spacer(Modifier.width(5.dp))
        }
        Text("$label ", style = MaterialTheme.typography.bodySmall, color = p.ink2)
        Text(if (valid) fmt1(value) else "–", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Text(" g", style = MaterialTheme.typography.bodySmall, color = p.ink2)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitDropdown(units: List<FoodUnit>, selected: String, modifier: Modifier, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun label(u: FoodUnit) = if (u.name == GRAM) "g (grams)" else "${isolate(u.name)} · ${fmt1(u.grams)} g"
    val current = units.firstOrNull { it.name == selected }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = current?.let { label(it) } ?: selected,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Unit") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            units.forEach { u ->
                DropdownMenuItem(
                    text = { Text(label(u), style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr)) },
                    onClick = {
                        onSelect(u.name)
                        open = false
                    },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- quick calories

@Composable
fun QuickForm(vm: AppViewModel, entry: Entry?, meal: String, onSaved: () -> Unit, onDelete: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    var name by remember(entry) { mutableStateOf(entry?.name ?: "") }
    var kcal by remember(entry) { mutableStateOf(entry?.kcal?.trimmed() ?: "") }
    var protein by remember(entry) { mutableStateOf(entry?.protein?.takeIf { it > 0 }?.trimmed() ?: "") }
    var carbs by remember(entry) { mutableStateOf(entry?.carbs?.takeIf { it > 0 }?.trimmed() ?: "") }
    var fat by remember(entry) { mutableStateOf(entry?.fat?.takeIf { it > 0 }?.trimmed() ?: "") }
    var busy by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        SubText("For a meal out or anything you only know the calories of.")
        OutlinedTextField(
            value = name, onValueChange = { name = it }, label = { Text("Description") },
            placeholder = { Text("Quick add") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField(kcal, { kcal = it }, "Calories", Modifier.weight(1f), suffix = "kcal")
            NumberField(protein, { protein = it }, "Protein", Modifier.weight(1f), suffix = "g", placeholder = "optional")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField(carbs, { carbs = it }, "Carbs", Modifier.weight(1f), suffix = "g", placeholder = "optional")
            NumberField(fat, { fat = it }, "Fat", Modifier.weight(1f), suffix = "g", placeholder = "optional", imeAction = ImeAction.Done)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onDelete != null) {
                OutlinedButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            }
            Spacer(Modifier.weight(1f))
            Button(
                enabled = !busy,
                modifier = Modifier.width(140.dp).height(48.dp),
                onClick = {
                    fun num(s: String) = if (s.isBlank()) 0.0 else parseNum(s)
                    val k = num(kcal)
                    val pr = num(protein)
                    val c = num(carbs)
                    val ft = num(fat)
                    if (k == null || pr == null || c == null || ft == null || k < 0 || pr < 0 || c < 0 || ft < 0) {
                        vm.toast("Check the numbers")
                        return@Button
                    }
                    if (k == 0.0 && pr + c + ft == 0.0) {
                        vm.toast("Enter calories")
                        return@Button
                    }
                    busy = true
                    scope.launch {
                        val ok = if (entry != null) {
                            vm.editEntry(entry, jsonBody("name" to name.ifBlank { "Quick add" }, "kcal" to k, "protein" to pr,
                                "carbs" to c, "fat" to ft, "meal" to meal))
                        } else {
                            vm.addQuick(name, k, pr, c, ft, meal)
                        }
                        busy = false
                        if (ok) {
                            val total = if (k > 0) k else pr * 4 + c * 4 + ft * 9
                            vm.toast(if (entry != null) "Saved" else "Added ${fmt(total)} kcal to ${mealLabel(meal).lowercase()}")
                            if (entry == null) {
                                name = ""; kcal = ""; protein = ""; carbs = ""; fat = ""
                            }
                            onSaved()
                        }
                    }
                },
            ) { Text(if (entry != null) "Save" else "Add") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickSheet(vm: AppViewModel, entry: Entry, initialMeal: String, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var meal by remember { mutableStateOf(initialMeal) }
    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
        ) {
            Text("Edit quick add", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Left)
            Spacer(Modifier.height(8.dp))
            MealSelector(meal, { meal = it })
            QuickForm(vm, entry, meal, onSaved = { close() }, onDelete = {
                scope.launch { if (vm.deleteEntry(entry)) close() }
            })
        }
    }
}
