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
import androidx.compose.material3.Button
import androidx.compose.ui.platform.LocalContext
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import org.json.JSONObject

// First-run screen (and Admin > Kobo setup): server, form UID, API token and admin PIN are typed here once
// and stay on this phone. Nothing is stored in the source code.
// What a setup QR code may contain: just the API key as plain text, or JSON such as
// {"token":"...","server":"https://kf.kobotoolbox.org","project_uid":"..","update_uid":"..","account_uid":".."}
class SetupQr(val token: String, val server: String?, val project: String?, val update: String?, val account: String?)

fun parseSetupQr(raw: String): SetupQr {
    val t = raw.trim()
    if (t.startsWith("{")) {
        return try {
            val j = JSONObject(t)
            fun opt(k: String): String? = j.optString(k).trim().ifEmpty { null }
            SetupQr(j.optString("token").trim(), opt("server"), opt("project_uid"), opt("update_uid"), opt("account_uid"))
        } catch (e: Exception) {
            SetupQr("", null, null, null, null)
        }
    }
    return SetupQr(t, null, null, null, null)
}

@Composable
fun SetupScreen(onDone: (Boolean) -> Unit, onCancel: (() -> Unit)?) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var server by remember { mutableStateOf(KoboConfig.KF_SERVER) }
    var projectUid by remember { mutableStateOf(KoboConfig.PROJECT_UID) }
    var updateUid by remember { mutableStateOf(KoboConfig.UPDATE_UID) }
    var accountUid by remember { mutableStateOf(KoboConfig.ACCOUNT_UID) }
    var token by remember { mutableStateOf(KoboConfig.TOKEN) }
    var pin by remember { mutableStateOf(KoboConfig.ADMIN_PIN) }
    var showToken by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }   // server, form UIDs and admin PIN are hidden for normal users
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf(false) }
    // First run opens the camera right away; the admin's "Kobo setup" opens the typed fields.
    var manual by remember { mutableStateOf(onCancel != null) }
    var camGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camGranted = it }
    LaunchedEffect(Unit) { if (!manual && !camGranted) camLauncher.launch(Manifest.permission.CAMERA) }

    fun validate(): String = when {
        !server.trim().startsWith("https://") -> "Server URL must start with https://"
        projectUid.isBlank() -> "Enter the Register Project form UID."
        updateUid.isBlank() -> "Enter the Daily Updates form UID."
        accountUid.isBlank() -> "Enter the Account Access form UID."
        setOf(projectUid, updateUid, accountUid).size < 3 -> "The three forms need three different UIDs."
        token.isBlank() -> "Enter the API token."
        pin.isNotEmpty() && pin.length !in 4..8 -> "Admin PIN must be 4 to 8 digits (or leave it empty)."
        else -> ""
    }

    // Tests all three forms. With autoContinue (after a QR scan on first run) a good result saves and continues.
    fun runTest(autoContinue: Boolean) {
        val problem = validate()
        if (problem.isNotEmpty()) { ok = false; msg = problem; return }
        busy = true
        scope.launch {
            var good = true
            var text = "Connected. All three forms were found."
            for ((name, uid) in listOf("Register Project" to projectUid, "Daily Updates" to updateUid, "Account Access" to accountUid)) {
                val (g, t) = testKoboConnection(server, uid, token)
                if (!g) { good = false; text = "$name: $t"; break }
            }
            ok = good; msg = text; busy = false
            if (good && autoContinue) onDone(KoboConfig.save(server, projectUid, updateUid, accountUid, token, pin))
        }
    }

    fun handleScanned(raw: String) {
        val q = parseSetupQr(raw)
        if (q.token.isEmpty()) {
            ok = false; msg = "This QR code does not contain an API key."
            manual = true
            return
        }
        token = q.token
        q.server?.let { server = it }
        q.project?.let { projectUid = it }
        q.update?.let { updateUid = it }
        q.account?.let { accountUid = it }
        msg = ""
        manual = true            // show the result (and the fields) while the connection is tested
        runTest(autoContinue = onCancel == null)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(24.dp))

        if (!manual) {
            // ---------- Camera mode ----------
            Text("Scan configuration QR code", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Text(
                "Point the camera at the QR code your admin gave you.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (camGranted) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp))) {
                    QrScannerView(onResult = { handleScanned(it) }, modifier = Modifier.fillMaxSize())
                }
            } else {
                MessageBanner(BannerKind.Error, "Camera permission is needed to scan the QR code.")
                Button(
                    onClick = { camLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
                ) { Text("Allow camera") }
            }
            Text("OR", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = { manual = true },
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
            ) { Text("Enter API key manually") }
            if (onCancel != null) TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        } else {
            // ---------- Typed fields ----------
            Text(if (onCancel == null) "Enter your API key" else "Kobo setup", style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(
                "Paste the API key your admin gave you. After that you can log in with your email and password.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = { msg = ""; manual = false },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
            ) { Text("Scan QR code") }
            AppCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        token, { token = it.trim(); msg = "" }, label = { Text("API Token") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { TextButton({ showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") } }
                    )
                    if (advanced) {
                        OutlinedTextField(
                            server, { server = it.trim(); msg = "" }, label = { Text("Server URL") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                        )
                        OutlinedTextField(
                            projectUid, { projectUid = it.trim(); msg = "" }, label = { Text("Register Project - form UID") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            updateUid, { updateUid = it.trim(); msg = "" }, label = { Text("Daily Updates - form UID") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            accountUid, { accountUid = it.trim(); msg = "" }, label = { Text("Account Access - form UID") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            pin, { pin = it.filter(Char::isDigit).take(8); msg = "" }, label = { Text("Admin PIN (4 to 8 digits)") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                        )
                    }
                }
            }
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.fillMaxWidth()) {
                Text(if (advanced) "Hide advanced settings" else "Advanced settings (server, forms, admin PIN)")
            }
            if (msg.isNotEmpty()) MessageBanner(if (ok) BannerKind.Success else BannerKind.Error, msg)
            if (busy) LoadingBox(label = "Testing connection...")

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { runTest(false) },
                    enabled = !busy && token.isNotBlank(),
                    modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)
                ) { Text("Test connection") }
                Button(
                    onClick = {
                        val problem = validate()
                        if (problem.isNotEmpty()) { ok = false; msg = problem }
                        else onDone(KoboConfig.save(server, projectUid, updateUid, accountUid, token, pin))
                    },
                    enabled = !busy,
                    modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)
                ) { Text(if (onCancel == null) "Continue" else "Save") }
            }
            OutlinedButton(
                onClick = {
                    KoboConfig.clear()
                    server = KoboConfig.KF_SERVER; projectUid = KoboConfig.PROJECT_UID; updateUid = KoboConfig.UPDATE_UID
                    accountUid = KoboConfig.ACCOUNT_UID; token = KoboConfig.TOKEN; pin = KoboConfig.ADMIN_PIN
                    ok = true; msg = "Configuration cleared."
                },
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
            ) { Text("Clear configuration", color = MaterialTheme.colorScheme.error) }
            if (onCancel != null) TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            Text(
                "Saved only on this phone. Each phone needs this once.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
