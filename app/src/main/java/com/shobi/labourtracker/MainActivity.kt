package com.shobi.labourtracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
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
        "summary" -> SummaryScreen(projects, entries) { u -> scope.launch { dao.saveUpdate(u) } }
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

    var selected by remember { mutableStateOf(projects.first()) }
    var projectMenu by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var progressText by remember { mutableStateOf("") }
    var skilledText by remember { mutableStateOf("") }
    var unskilledText by remember { mutableStateOf("") }
    var completed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    val numberKeyboard = KeyboardOptions(keyboardType = KeyboardType.Number)
    val recentDates = remember { (-2..2).map { LocalDate.now().plusDays(it.toLong()) } }

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
                            onClick = { selected = p; projectMenu = false }
                        )
                    }
                }
            }

            // Date strip
            Text("DATE", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                recentDates.forEach { d ->
                    val isSelected = d == date
                    Surface(
                        onClick = { date = d; error = "" },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = if (isSelected) 0.dp else 1.dp
                    ) {
                        Column(Modifier.width(68.dp).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(d.dayOfWeek.name.take(3), style = MaterialTheme.typography.labelSmall, color = if (isSelected) Color.White.copy(alpha = .8f) else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                            Text(d.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                            Text(d.month.name.take(3), style = MaterialTheme.typography.labelSmall, color = if (isSelected) Color.White.copy(alpha = .8f) else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            OutlinedTextField(
                value = date.toString(),
                onValueChange = { value -> runCatching { LocalDate.parse(value) }.getOrNull()?.let { date = it }; if (value.length >= 8) error = "" },
                label = { Text("Date (yyyy-MM-dd)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Text("▣", color = MaterialTheme.colorScheme.primary) }
            )

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
                        else -> onSave(DailyUpdate(selected.drrCode, date.toString(), if (completed || p >= 100) "Completed" else "Ongoing", p, s, u))
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
fun SummaryScreen(
    projects: List<Project>,
    entries: List<DailyEntry>,
    onSaveUpdate: (DailyUpdate) -> Unit
) {
    val today = LocalDate.now()
    val dates = remember(entries) {
        val all = entries.map { it.date }.distinct().sorted()
        if (all.isEmpty()) listOf(today) else all
    }
    val latest = entries.maxByOrNull { it.date }
    val totalWorkers = entries.sumOf { it.skilled + it.unskilled }
    val activeProjects = projects.count { pr ->
        val e = entries.filter { it.drrCode == pr.drrCode }.maxByOrNull { it.date }
        (e?.progress ?: pr.progress) < 100
    }
    var editing by remember { mutableStateOf<DailyEntry?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Progress Summary", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Tap any date cell to edit the daily update",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SummaryMetric("Projects", projects.size.toString(), Modifier.weight(1f))
            SummaryMetric("Active", activeProjects.toString(), Modifier.weight(1f))
            SummaryMetric("Workers", totalWorkers.toString(), Modifier.weight(1f))
        }

        Surface(
            modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Latest update", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f))
                    Text(
                        latest?.date?.toString() ?: "No updates yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                if (latest != null) {
                    Text(
                        "${latest.progress}%",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        Text(
            "DAILY PROJECT MATRIX",
            modifier = Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        if (projects.isEmpty()) {
            Surface(
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("No projects yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Register a project and add a daily update to see it here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
            ) {
                Row(Modifier.wrapContentWidth()) {
                    MatrixProjectHeader()
                    dates.forEach { date -> DateHeader(date, date == today) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                projects.forEachIndexed { index, project ->
                    Row(Modifier.wrapContentWidth()) {
                        ProjectMatrixLabel(project, index)
                        dates.forEach { date ->
                            val entry = entries.firstOrNull { it.drrCode == project.drrCode && it.date == date }
                            MatrixCell(entry) { editing = it }
                        }
                    }
                    if (index < projects.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                    }
                }
            }
        }

        Row(
            Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Tip:", fontWeight = FontWeight.Bold)
            Text("Swipe left/right to see more dates.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    editing?.let { entry ->
        EditSummaryDialog(
            entry = entry,
            onDismiss = { editing = null },
            onSave = { updated ->
                onSaveUpdate(updated)
                editing = null
            }
        )
    }
}

@Composable
private fun SummaryMetric(title: String, value: String, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

private val projectColumnWidth = 190.dp
private val dateColumnWidth = 112.dp

@Composable
private fun MatrixProjectHeader() {
    Surface(
        modifier = Modifier.width(projectColumnWidth).height(64.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
            Text("PROJECT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("Sub-block / Activity", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DateHeader(date: LocalDate, isToday: Boolean) {
    Surface(
        modifier = Modifier.width(dateColumnWidth).height(64.dp),
        color = if (isToday) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column(Modifier.padding(horizontal = 10.dp), verticalArrangement = Arrangement.Center) {
            Text(
                date.dayOfWeek.name.take(3),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "${date.dayOfMonth.toString().padStart(2, '0')} ${date.month.name.take(3)}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            if (isToday) Text("TODAY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ProjectMatrixLabel(project: Project, index: Int) {
    Surface(
        modifier = Modifier.width(projectColumnWidth).height(82.dp),
        color = if (index % 2 == 0) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.Center) {
            Text(project.subBlock, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(project.activity, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("DRR ${project.drrCode}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MatrixCell(entry: DailyEntry?, onClick: (DailyEntry) -> Unit) {
    Surface(
        modifier = Modifier.width(dateColumnWidth).height(82.dp),
        onClick = { entry?.let(onClick) },
        enabled = entry != null,
        shape = RoundedCornerShape(0.dp),
        color = if (entry == null) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        if (entry == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("—", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
            }
        } else {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${entry.progress}%", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    Text("✎", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                LinearProgressIndicator(
                    progress = { entry.progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth().height(5.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Text("S ${entry.skilled}  •  U ${entry.unskilled}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EditSummaryDialog(
    entry: DailyEntry,
    onDismiss: () -> Unit,
    onSave: (DailyUpdate) -> Unit
) {
    var progress by remember(entry) { mutableStateOf(entry.progress.toString()) }
    var skilled by remember(entry) { mutableStateOf(entry.skilled.toString()) }
    var unskilled by remember(entry) { mutableStateOf(entry.unskilled.toString()) }
    var error by remember { mutableStateOf("") }
    val num = KeyboardOptions(keyboardType = KeyboardType.Number)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit daily update", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${entry.subBlock} • ${entry.date}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(progress, { progress = it }, label = { Text("Progress %") }, keyboardOptions = num, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(skilled, { skilled = it }, label = { Text("Skilled") }, keyboardOptions = num, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(unskilled, { unskilled = it }, label = { Text("Unskilled") }, keyboardOptions = num, modifier = Modifier.weight(1f), singleLine = true)
                }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                val p = progress.toIntOrNull()
                val s = skilled.toIntOrNull()
                val u = unskilled.toIntOrNull()
                if (p == null || p !in 0..100 || s == null || s < 0 || u == null || u < 0) {
                    error = "Use valid numbers. Progress must be 0–100."
                } else {
                    onSave(DailyUpdate(entry.drrCode, entry.date.toString(), if (p >= 100) "Completed" else "Ongoing", p, s, u))
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
