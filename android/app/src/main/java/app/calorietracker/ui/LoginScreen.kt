package app.calorietracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.calorietracker.AppViewModel
import app.calorietracker.data.ApiException
import app.calorietracker.data.Me
import app.calorietracker.data.normalizeServerUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(vm: AppViewModel, snackbar: SnackbarHostState) {
    var server by rememberSaveable { mutableStateOf(vm.serverUrl) }
    var user by rememberSaveable { mutableStateOf(vm.username) }
    var password by rememberSaveable { mutableStateOf("") }
    var signup by rememberSaveable { mutableStateOf(false) }
    var me by remember { mutableStateOf<Me?>(null) }
    var serverNote by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val palette = LocalPalette.current

    // Check the address shortly after the user stops typing.
    LaunchedEffect(server) {
        me = null
        serverNote = ""
        if (server.isBlank()) return@LaunchedEffect
        delay(700)
        try {
            val info = vm.checkServer(server)
            me = info
            if (info.firstUser) signup = true
            if (!info.signupAllowed) signup = false
            serverNote = when {
                info.firstUser -> "Connected. No accounts yet: create the first one."
                else -> "Connected."
            }
        } catch (e: ApiException) {
            serverNote = e.message ?: "Can't reach the server"
        }
    }

    fun submit() {
        if (busy) return
        error = ""
        if (server.isBlank() || user.isBlank() || password.isEmpty()) {
            error = "Fill in the server address, username and password."
            return
        }
        busy = true
        scope.launch {
            try {
                vm.login(server, user, password, signup)
            } catch (e: ApiException) {
                error = e.message ?: "Login failed"
            } finally {
                busy = false
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Daybook", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (signup) "Create your account on your server." else "Log in to your server.",
                    color = palette.ink2,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it.trim() },
                    label = { Text("Server address") },
                    placeholder = { Text("daybook.example.com or 1.2.3.4:8080") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    supportingText = {
                        val insecure = server.isNotBlank() && normalizeServerUrl(server).startsWith("http://")
                        Text(
                            listOfNotNull(
                                serverNote.ifBlank { null },
                                if (insecure) "Plain http: your password isn't encrypted. Use https if you can." else null,
                            ).joinToString(" "),
                        )
                    },
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { submit() }),
                    supportingText = if (signup) ({ Text("At least 6 characters") }) else null,
                )
                if (error.isNotEmpty()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    if (busy) CircularProgressIndicator(Modifier.height(20.dp).widthIn(max = 20.dp), strokeWidth = 2.dp)
                    else Text(if (signup) "Create account" else "Log in")
                }
                val info = me
                if (info != null && info.signupAllowed && !info.firstUser) {
                    TextButton(onClick = { signup = !signup }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(if (signup) "I already have an account" else "Create an account")
                    }
                }
            }
        }
    }
}
