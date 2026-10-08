@file:OptIn(ExperimentalMaterial3Api::class)

package com.shobi.labourtracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colors = lightColorScheme(
                primary = Color(0xFF1769AA),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFD7E9FF),
                onPrimaryContainer = Color(0xFF001D35),
                surface = Color(0xFFF8FAFC),
                surfaceContainerLow = Color(0xFFF0F4F8),
                surfaceVariant = Color(0xFFE1E7EF)
            )
            MaterialTheme(colorScheme = colors) { Surface(Modifier.fillMaxSize()) { App() } }
        }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("app", 0) }
    val dao = remember { AppDb.get(ctx).dao() }
    val scope = rememberCoroutineScope()
    var block by remember { mutableStateOf(prefs.getString("block", null)) }
    var isAdmin by remember { mutableStateOf(prefs.getBoolean("admin", false)) }
    var screen by remember { mutableStateOf("menu") }
    var detailCode by remember { mutableStateOf("") }
    var detailBack by remember { mutableStateOf("menu") }

    if (isAdmin) {
        AdminApp(dao) { prefs.edit().remove("admin").apply(); isAdmin = false }
        return
    }
    val b = block
    if (b == null) {
        LoginScreen(
            dao = dao,
            onLogin = { block = it; prefs.edit().putString("block", it).apply() },
            onAdmin = { prefs.edit().putBoolean("admin", true).apply(); isAdmin = true }
        )
        return
    }
    val projects by dao.projects(b).collectAsState(emptyList())
    val updates by dao.allUpdates().collectAsState(emptyList())
    val everyProject by dao.allProjects().collectAsState(emptyList())
    val byCode = projects.associateBy { it.drrCode }
    val entries = updates.mapNotNull { u -> byCode[u.drrCode]?.let { u.toEntry(it) } }

    BackHandler(enabled = screen != "menu") { screen = if (screen == "project") detailBack else "menu" }
    when (screen) {
        "menu" -> DashboardScreen(b, projects, entries, { s ->
            if (s.startsWith("project:")) { detailCode = s.removePrefix("project:"); detailBack = "menu"; screen = "project" }
            else screen = s
        })
        "register" -> RegisterProjectScreen(b, everyProject.associateBy { it.drrCode }) { p -> scope.launch { dao.saveProject(p); syncAll(dao, b) }; screen = "menu" }
        "update" -> DailyUpdateScreen(projects) { u, p ->
            scope.launch { if (p != null) dao.saveProject(p); dao.saveUpdate(u); syncAll(dao, b) }
            screen = "menu"
        }
        "summary" -> SummaryScreen("Progress Summary", projects.sortedBy { it.subBlock }, entries) {
            detailCode = it; detailBack = "summary"; screen = "project"
        }
        "project" -> {
            val p = byCode[detailCode]
            if (p == null) screen = "menu"
            else ProjectDetailScreen(p, entries) { u -> scope.launch { dao.saveUpdate(u); syncAll(dao, b) } }
        }
        "report" -> ReportScreen(b, entries)
        "projectupdate" -> ProjectUpdateScreen(listOf(b), projects, entries)
        "sync" -> SyncScreen(b, dao) { prefs.edit().remove("block").apply(); block = null; screen = "menu" }
    }
}

@Composable
fun LoginScreen(dao: AppDao, onLogin: (String) -> Unit, onAdmin: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var askPin by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Labour Tracker", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("Log in with the email and password from your admin.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            email, { email = it.trim(); error = "" }, label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            pw, { pw = it.filter(Char::isDigit).take(8); error = "" },
            label = { Text("Password (6 or 8 digits)") }, singleLine = true,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            trailingIcon = { TextButton({ show = !show }) { Text(if (show) "Hide" else "Show") } },
            modifier = Modifier.fillMaxWidth()
        )
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = {
                busy = true
                scope.launch {
                    val (blk, msg) = login(dao, email, pw)
                    busy = false
                    if (blk != null) onLogin(blk) else error = msg
                }
            },
            enabled = !busy && email.isNotBlank() && (pw.length == 6 || pw.length == 8),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(if (busy) "Checking..." else "Log in") }
        Spacer(Modifier.height(16.dp))
        OutlinedButton({ askPin = true; pin = ""; wrong = false }, Modifier.fillMaxWidth()) { Text("Admin login") }
    }
    if (askPin) {
        AlertDialog(
            onDismissRequest = { askPin = false },
            title = { Text("Admin PIN", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    pin, { pin = it.filter(Char::isDigit).take(8); wrong = false },
                    label = { Text("PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = wrong,
                    supportingText = { if (wrong) Text("Wrong PIN") }
                )
            },
            confirmButton = {
                Button({ if (pin == KoboConfig.ADMIN_PIN) { askPin = false; onAdmin() } else wrong = true }) { Text("Login") }
            },
            dismissButton = { TextButton({ askPin = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun DailyUpdateScreen(projects: List<Project>, onSave: (DailyUpdate, Project?) -> Unit) {
    if (projects.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) { Text("＋", Modifier.padding(18.dp), fontSize = 28.sp, color = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.height(16.dp))
            Text("No project yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Register a project before adding a daily update.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    var selectedCode by remember { mutableStateOf(projects.first().drrCode) }
    val selected = projects.firstOrNull { it.drrCode == selectedCode } ?: projects.first()
    var endText by remember(selected.drrCode, selected.endDate) { mutableStateOf(selected.endDate) }
    var projectMenu by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var progressText by remember { mutableStateOf("") }
    var skilledText by remember { mutableStateOf("") }
    var unskilledText by remember { mutableStateOf("") }
    var completed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val numberKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number)
    var showDatePicker by remember { mutableStateOf(false) }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEE, dd MMM yyyy", Locale.ENGLISH) }
    fun dateNote(d: LocalDate) = when (d) {
        LocalDate.now() -> "Today"
        LocalDate.now().minusDays(1) -> "Yesterday"
        else -> ""
    }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
    ) {
        // Hero header
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp)) {
                Text("DAILY UPDATE", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = .75f), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Record today's progress", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Update workforce and project completion in one place.", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = .88f))
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Project selector
            Text("PROJECT", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Box {
                Surface(
                    onClick = { projectMenu = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 1.dp
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text("▦", Modifier.padding(horizontal = 13.dp, vertical = 10.dp), fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(selected.subBlock.ifBlank { "Sub-block" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(selected.activity, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("DRR ${selected.drrCode}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Text("⌄", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                DropdownMenu(expanded = projectMenu, onDismissRequest = { projectMenu = false }) {
                    projects.forEach { p ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text("${p.subBlock} • ${p.activity}", fontWeight = FontWeight.SemiBold)
                                    Text("DRR ${p.drrCode}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            onClick = { selectedCode = p.drrCode; projectMenu = false }
                        )
                    }
                }
            }

            // Date: tap to open the calendar
            Text("DATE", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Surface(
                onClick = { showDatePicker = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text("▣", Modifier.padding(horizontal = 13.dp, vertical = 10.dp), fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(date.format(dateFormat), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (dateNote(date).isNotEmpty()) {
                            Text(dateNote(date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text("⌄", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (showDatePicker) {
                val pickerState = rememberDatePickerState(
                    initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                    selectableDates = object : SelectableDates {
                        // no future dates
                        override fun isSelectableDate(utcTimeMillis: Long) =
                            !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(LocalDate.now())
                    }
                )
                DatePickerDialog(
                    onDismissRequest = { showDatePicker = false },
                    confirmButton = {
                        TextButton({
                            pickerState.selectedDateMillis?.let {
                                date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                                error = ""
                            }
                            showDatePicker = false
                        }) { Text("OK") }
                    },
                    dismissButton = { TextButton({ showDatePicker = false }) { Text("CANCEL") } }
                ) { DatePicker(state = pickerState) }
            }

            // Progress card
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("PROJECT PROGRESS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Text("How much is completed?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedTextField(
                            value = progressText,
                            onValueChange = { v -> progressText = v.filter(Char::isDigit).take(3); error = "" },
                            modifier = Modifier.width(88.dp),
                            singleLine = true,
                            keyboardOptions = numberKeyboard,
                            suffix = { Text("%") }
                        )
                    }
                    val progress = progressText.toIntOrNull()?.coerceIn(0, 100) ?: 0
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("0%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Current: $progress%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text("100%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // Project end date
            OutlinedTextField(
                value = endText,
                onValueChange = { endText = it.trim().take(10); error = "" },
                label = { Text("Project end date (yyyy-MM-dd)") },
                supportingText = { Text("Optional. Empty + project completed = today's update date.") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Text("⚑", color = MaterialTheme.colorScheme.primary) }
            )

            // Workforce cards
            Text("TODAY'S WORKFORCE", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WorkforceInputCard("SKILLED", skilledText, { skilledText = it.filter(Char::isDigit).take(3); error = "" }, Modifier.weight(1f), numberKeyboard)
                WorkforceInputCard("UNSKILLED", unskilledText, { unskilledText = it.filter(Char::isDigit).take(3); error = "" }, Modifier.weight(1f), numberKeyboard)
            }
            val total = (skilledText.toIntOrNull() ?: 0) + (unskilledText.toIntOrNull() ?: 0)
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("TOTAL WORKFORCE", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.weight(1f))
                    Text(total.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }

            // Completion status
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(12.dp), color = if (completed) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                        Text(if (completed) "✓" else "○", Modifier.padding(11.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (completed) "Project completed" else "Project ongoing", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(if (completed) "Mark this update as completed" else "Keep this project open for the next update", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = completed, onCheckedChange = { completed = it })
                }
            }

            if (error.isNotEmpty()) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Text(error, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                }
            }

            Button(
                onClick = {
                    val p = progressText.toIntOrNull()
                    val s = skilledText.toIntOrNull()
                    val u = unskilledText.toIntOrNull()
                    when {
                        p == null || p !in 0..100 -> error = "Enter progress from 0 to 100."
                        s == null || s < 0 -> error = "Enter a valid skilled worker count."
                        u == null || u < 0 -> error = "Enter a valid unskilled worker count."
                        else -> {
                            val endRaw = endText.trim()
                            val endParsed = if (endRaw.isEmpty()) null else runCatching { LocalDate.parse(endRaw) }.getOrNull()
                            val startParsed = runCatching { LocalDate.parse(selected.startDate) }.getOrNull()
                            val isDone = completed || p >= 100
                            when {
                                endRaw.isNotEmpty() && endParsed == null -> error = "End date must look like 2026-12-31."
                                endParsed != null && startParsed != null && endParsed.isBefore(startParsed) ->
                                    error = "End date cannot be before the start date."
                                else -> {
                                    val finalEnd = if (endRaw.isEmpty() && isDone) date.toString() else endRaw
                                    val changed = if (finalEnd != selected.endDate) selected.copy(endDate = finalEnd, synced = false) else null
                                    onSave(DailyUpdate(selected.drrCode, date.toString(), if (isDone) "Completed" else "Ongoing", p, s, u), changed)
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp)
            ) { Text("Save Daily Update", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }

            Text(
                "Your update is saved offline and can be synced to Kobo later.",
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun WorkforceInputCard(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
    keyboardOptions: KeyboardOptions
) {
    Surface(modifier = modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("0") },
                singleLine = true,
                keyboardOptions = keyboardOptions,
                textStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
        }
    }
}

@Composable
fun ReportScreen(block: String, entries: List<DailyEntry>) {
    val ctx = LocalContext.current
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    val d = runCatching { LocalDate.parse(date) }.getOrNull()
    val text = if (d != null) WhatsAppReport.blockReport(block, d, entries) else "Wrong date"
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("WhatsApp Report", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(date, { date = it }, label = { Text("Report date (yyyy-MM-dd)") }, modifier = Modifier.fillMaxWidth())
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Button({ shareToWhatsApp(ctx, text) }, Modifier.fillMaxWidth(), enabled = d != null) { Text("Send on WhatsApp") }
    }
}
