@file:OptIn(ExperimentalMaterial3Api::class)

package com.shobi.labourtracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// First-run screen (and Admin > Kobo setup): server, form UID, API token and admin PIN are typed here once
// and stay on this phone. Nothing is stored in the source code.
@Composable
fun SetupScreen(onDone: () -> Unit, onCancel: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(KoboConfig.KF_SERVER) }
    var uid by remember { mutableStateOf(KoboConfig.FORM_UID) }
    var token by remember { mutableStateOf(KoboConfig.TOKEN) }
    var pin by remember { mutableStateOf(KoboConfig.ADMIN_PIN) }
    var showToken by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf(false) }

    fun validate(): String = when {
        !server.trim().startsWith("https://") -> "Server URL must start with https://"
        uid.isBlank() -> "Enter the Project / Asset UID."
        token.isBlank() -> "Enter the API token."
        pin.length !in 4..8 -> "Admin PIN must be 4 to 8 digits."
        else -> ""
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Kobo setup", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(
            "Step 1: connect this phone to your KoboToolbox form. Step 2: log in. " +
                "The block members (email + password) are then created by the Admin under Admin login > Block accounts.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    server, { server = it.trim(); msg = "" }, label = { Text("Server URL") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                OutlinedTextField(
                    uid, { uid = it.trim(); msg = "" }, label = { Text("Project / Asset UID") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    token, { token = it.trim(); msg = "" }, label = { Text("API Token") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { TextButton({ showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") } }
                )
                OutlinedTextField(
                    pin, { pin = it.filter(Char::isDigit).take(8); msg = "" }, label = { Text("Admin PIN (4 to 8 digits)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
            }
        }
        if (msg.isNotEmpty()) MessageBanner(if (ok) BannerKind.Success else BannerKind.Error, msg)
        if (busy) LoadingBox(label = "Testing connection...")

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    busy = true
                    scope.launch {
                        val (good, text) = testKoboConnection(server, uid, token)
                        ok = good; msg = text; busy = false
                    }
                },
                enabled = !busy && token.isNotBlank(),
                modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)
            ) { Text("Test connection") }
            Button(
                onClick = {
                    val problem = validate()
                    if (problem.isNotEmpty()) { ok = false; msg = problem }
                    else { KoboConfig.save(server, uid, token, pin); onDone() }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)
            ) { Text("Save") }
        }
        OutlinedButton(
            onClick = {
                KoboConfig.clear()
                server = KoboConfig.KF_SERVER; uid = KoboConfig.FORM_UID; token = KoboConfig.TOKEN; pin = KoboConfig.ADMIN_PIN
                ok = true; msg = "Configuration cleared."
            },
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
        ) { Text("Clear configuration", color = MaterialTheme.colorScheme.error) }
        if (onCancel != null) TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        Text(
            "Saved only on this phone. Each phone that logs in needs this setup once.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
