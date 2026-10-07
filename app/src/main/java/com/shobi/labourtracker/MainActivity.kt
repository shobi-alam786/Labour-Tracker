package com.shobi.labourtracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate

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
    var screen by remember { mutableStateOf("menu") }

    val b = block
    if (b == null) {
        BlockPicker { block = it; prefs.edit().putString("block", it).apply() }
        return
    }
    val projects by dao.projects(b).collectAsState(emptyList())
    val updates by dao.allUpdates().collectAsState(emptyList())
    val byCode = projects.associateBy { it.drrCode }
    val entries = updates.mapNotNull { u -> byCode[u.drrCode]?.let { u.toEntry(it) } }

    BackHandler(enabled = screen != "menu") { screen = "menu" }
    when (screen) {
        "menu" -> DashboardScreen(b, projects, entries, { screen = it })
        "register" -> RegisterProjectScreen(b) { p -> scope.launch { dao.saveProject(p) }; screen = "menu" }
        "update" -> DailyUpdateScreen(projects) { u -> scope.launch { dao.saveUpdate(u) }; screen = "menu" }
        "summary" -> SummaryScreen(projects, entries)
        "report" -> ReportScreen(b, entries)
        "sync" -> SyncScreen(b, dao)
        "admin" -> AdminScreen(dao)
    }
}

@Composable
fun BlockPicker(onPick: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Select your block", style = MaterialTheme.typography.headlineSmall)
        "ABCDEFG".forEach { c ->
            Button({ onPick(c.toString()) }, Modifier.fillMaxWidth()) { Text("Block $c") }
        }
    }
}

@Composable
fun DailyUpdateScreen(projects: List<Project>, onSave: (DailyUpdate) -> Unit) {
    if (projects.isEmpty()) {
        Text("Please register a project first.", Modifier.padding(16.dp))
        return
    }
    var sel by remember { mutableStateOf(projects.first()) }
    var open by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var done by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var skilled by remember { mutableStateOf("") }
    var unskilled by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val num = KeyboardOptions(keyboardType = KeyboardType.Number)

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Daily Update", style = MaterialTheme.typography.titleLarge)
        Box {
            OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text("${sel.subBlock} - ${sel.activity}") }
            DropdownMenu(open, { open = false }) {
                projects.forEach { p ->
                    DropdownMenuItem(
                        text = { Text("${p.subBlock} - ${p.activity} (${p.drrCode})") },
                        onClick = { sel = p; open = false })
                }
            }
        }
        OutlinedTextField(date, { date = it }, label = { Text("Date (yyyy-MM-dd)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(progress, { progress = it }, label = { Text("Progress % (0-100)") }, keyboardOptions = num, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(skilled, { skilled = it }, label = { Text("Skilled workers") }, keyboardOptions = num, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(unskilled, { unskilled = it }, label = { Text("Unskilled workers") }, keyboardOptions = num, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Completed"); Switch(done, { done = it })
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = {
                val d = runCatching { LocalDate.parse(date) }.getOrNull()
                val p = progress.toIntOrNull()
                val s = skilled.toIntOrNull()
                val u = unskilled.toIntOrNull()
                if (d == null || p == null || p !in 0..100 || s == null || s < 0 || u == null || u < 0)
                    error = "Please check the date and numbers."
                else onSave(DailyUpdate(sel.drrCode, d.toString(), if (done) "Completed" else "Ongoing", p, s, u))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save Update") }
    }
}

@Composable
fun SummaryScreen(projects: List<Project>, entries: List<DailyEntry>) {
    val t = Summary.total(entries.filter { it.date == LocalDate.now() })
    val p = Summary.total(entries)
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Summary", style = MaterialTheme.typography.titleLarge)
        Text("Today: Skilled ${t.skilled} | Unskilled ${t.unskilled}")
        Text("Project total: Skilled ${p.skilled} | Unskilled ${p.unskilled}")
        HorizontalDivider()
        projects.forEach { pr ->
            val s = Summary.total(entries.filter { it.drrCode == pr.drrCode })
            Text("${pr.subBlock} ${pr.activity}\nSkilled ${s.skilled} | Unskilled ${s.unskilled}")
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
