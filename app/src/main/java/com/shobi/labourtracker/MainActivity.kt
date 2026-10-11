@file:OptIn(ExperimentalMaterial3Api::class)

package com.shobi.labourtracker

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KoboConfig.init(this)
        ReminderNotifier.ensureChannel(this)
        setContent { LabourTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

private fun titleFor(screen: String): String = when (screen) {
    "projects" -> "Projects"
    "update" -> "Daily update"
    "register" -> "Register project"
    "summary" -> "Progress summary"
    "project" -> "Project details"
    "report" -> "WhatsApp report"
    "projectupdate" -> "Daily project message"
    "sync" -> "Kobo sync"
    "reminder" -> "Daily reminder"
    else -> ""
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("app", 0) }
    val dao = remember { AppDb.get(ctx).dao() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var block by remember { mutableStateOf(prefs.getString("block", null)) }
    var isAdmin by remember { mutableStateOf(prefs.getBoolean("admin", false)) }
    var screen by remember { mutableStateOf("menu") }
    var detailCode by remember { mutableStateOf("") }
    var detailBack by remember { mutableStateOf("menu") }
    var listFilter by remember { mutableStateOf(StatusFilter.All) }
    var showSetup by remember { mutableStateOf(!KoboConfig.configured) }

    // One time after the upgrade to three Kobo forms: mark everything on the phone as "not sent yet".
    LaunchedEffect(Unit) { if (KoboConfig.consumeFormsMigration()) dao.markAllUnsynced() }

    // Android 13+: ask once for the notification permission (needed to show the 9:30 reminder).
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    if (isAdmin) {
        AdminApp(dao) { prefs.edit().remove("admin").apply(); isAdmin = false }
        return
    }
    // First run (or Kobo cleared): set up Kobo first, then log in.
    if (showSetup) {
        SetupScreen(onDone = { changed -> if (changed) scope.launch { dao.markAllUnsynced() }; showSetup = false }, onCancel = if (KoboConfig.configured) ({ showSetup = false }) else null)
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

    // Every time the app opens for a logged-in block: keep exactly one 9:30 alarm scheduled, fix statuses
    // that were saved before this version, and (silently) catch up if the 9:30 check was missed.
    LaunchedEffect(b) {
        ReminderScheduler.scheduleNext(ctx)
        StatusEngine.backfillCompletedOnce(dao, prefs)
        StatusEngine.runDailyCheck(dao, b, AppTime.now())
        if (ReminderHealth.needsNotificationPermission(ctx) && !ReminderPrefs.notificationAsked(ctx)) {
            ReminderPrefs.setNotificationAsked(ctx)
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val projectsState by dao.projects(b).collectAsState(null)
    val projects = projectsState ?: emptyList()
    val loading = projectsState == null
    val updates by dao.allUpdates().collectAsState(emptyList())
    val everyProject by dao.allProjects().collectAsState(emptyList())
    val byCode = projects.associateBy { it.drrCode }
    val entries = updates.mapNotNull { u -> byCode[u.drrCode]?.let { u.toEntry(it) } }

    fun go(s: String) {
        when {
            s.startsWith("project:") -> { detailCode = s.removePrefix("project:"); detailBack = screen; screen = "project" }
            s.startsWith("projects:") -> {
                listFilter = StatusFilter.values().firstOrNull { it.name == s.removePrefix("projects:") } ?: StatusFilter.All
                screen = "projects"
            }
            s == "projects" -> { listFilter = StatusFilter.All; screen = "projects" }
            else -> screen = s
        }
    }

    // After a save: try to send, then tell the person what happened (works offline too).
    fun saveThen(work: suspend () -> Unit) {
        scope.launch {
            work()
            val result = syncAll(dao, b)
            snackbar.showSnackbar(friendlySyncMessage(result))
        }
    }

    BackHandler(enabled = screen != "menu") { screen = if (screen == "project") detailBack else "menu" }

    val tabs = listOf("menu", "register", "projects", "update", "sync")
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (screen != "menu") {
                TopAppBar(
                    title = { Text(titleFor(screen), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { screen = if (screen == "project") detailBack else "menu" }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (screen in tabs) {
                NavigationBar {
                    NavigationBarItem(selected = screen == "menu", onClick = { screen = "menu" },
                        icon = { Icon(Icons.Filled.Home, null) }, label = { Text("Home") })
                    NavigationBarItem(selected = screen == "register", onClick = { screen = "register" },
                        icon = { Icon(Icons.Filled.Add, null) }, label = { Text("Register") })
                    NavigationBarItem(selected = screen == "projects", onClick = { go("projects") },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, null) }, label = { Text("Projects") })
                    NavigationBarItem(selected = screen == "update", onClick = { screen = "update" },
                        icon = { Icon(Icons.Filled.Edit, null) }, label = { Text("Update") })
                    NavigationBarItem(selected = screen == "sync", onClick = { screen = "sync" },
                        icon = { Icon(Icons.Filled.Refresh, null) }, label = { Text("Sync") })
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (screen) {
                "menu" -> DashboardScreen(b, projects, entries, loading, { go(it) })
                "projects" -> ProjectsScreen(projects, entries, loading, listFilter, { go("project:$it") }, { screen = "register" })
                "register" -> RegisterProjectScreen(b, everyProject.associateBy { it.drrCode }) { p ->
                    saveThen { dao.saveProject(p) }
                    screen = "menu"
                }
                "update" -> DailyUpdateScreen(projects) { u, p ->
                    saveThen {
                        if (p != null) StatusEngine.saveProjectKeepingStatus(dao, p)
                        val latestBefore = dao.latestUpdateDate(u.drrCode)   // read BEFORE saving
                        dao.saveUpdate(u)
                        StatusEngine.applyAfterSave(dao, u, latestBefore)
                    }
                    screen = "menu"
                }
                "summary" -> SummaryScreen("Progress Summary", projects.sortedBy { it.subBlock }, entries) {
                    detailCode = it; detailBack = "summary"; screen = "project"
                }
                "project" -> {
                    val p = byCode[detailCode]
                    if (p == null) screen = "menu"
                    else ProjectDetailScreen(
                        p, entries,
                        onEdit = { u -> saveThen { dao.saveUpdate(u) } },   // editing a past day never changes the project status
                        onMarkCompleted = { saveThen { StatusEngine.markCompleted(dao, p.drrCode) } },
                        onReopen = { scope.launch { StatusEngine.reopen(dao, p.drrCode) } }
                    )
                }
                "report" -> ReportScreen(b, entries)
                "projectupdate" -> ProjectUpdateScreen(listOf(b), projects, entries)
                "reminder" -> ReminderScreen()
                "sync" -> SyncScreen(b, dao, { prefs.edit().remove("block").apply(); block = null; screen = "menu" }, { showSetup = true })
            }
        }
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
                    supportingText = { if (wrong) Text(if (KoboConfig.ADMIN_PIN.isEmpty()) "Admin PIN is not set in this build" else "Wrong PIN") }
                )
            },
            confirmButton = {
                // An empty configured PIN means "admin login off": it must never match an empty entry.
                Button({ if (KoboConfig.ADMIN_PIN.isNotEmpty() && pin == KoboConfig.ADMIN_PIN) { askPin = false; onAdmin() } else wrong = true }) { Text("Login") }
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
    var projectMenu by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(AppTime.today()) }
    var progressText by remember { mutableStateOf("") }
    var skilledText by remember { mutableStateOf("") }
    var unskilledText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val numberKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number)
    var showDatePicker by remember { mutableStateOf(false) }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEE, dd MMM yyyy", Locale.ENGLISH) }
    fun dateNote(d: LocalDate) = when (d) {
        AppTime.today() -> "Today"
        AppTime.today().minusDays(1) -> "Yesterday"
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

            if (selected.projectStatus() == ProjectStatus.Completed) {
                MessageBanner(
                    BannerKind.Info,
                    "This project is marked Completed. Saving an update keeps it Completed. Use Reopen in Project details if work resumes."
                )
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
                            !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(AppTime.today())
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
                        // Completion is done with "Mark project as completed" in Project details (it also sets the end date).
                        else -> onSave(DailyUpdate(selected.drrCode, date.toString(), "Ongoing", p, s, u), null)
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
    var date by remember { mutableStateOf(AppTime.today().toString()) }
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
