@file:OptIn(ExperimentalMaterial3Api::class)

package com.shobi.labourtracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFormat = DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy", Locale.ENGLISH)

// Navigation strings used by `go`:
//   "update", "register", "summary", "sync", "projectupdate", "reminder", "report", "projects"
//   "projects:Pending" | "projects:Ongoing" | "projects:Completed"   (list with a filter)
//   "project:<drrCode>"                                                (project details)
@Composable
fun DashboardScreen(
    block: String,
    projects: List<Project>,
    entries: List<DailyEntry>,
    loading: Boolean,
    go: (String) -> Unit,
    today: LocalDate = AppTime.today()
) {
    val ctx = LocalContext.current
    val todayEntries = entries.filter { it.date == today }
    val skilled = todayEntries.sumOf { it.skilled }
    val unskilled = todayEntries.sumOf { it.unskilled }
    val totalWorkers = skilled + unskilled

    val pending = projects.count { it.projectStatus() == ProjectStatus.Pending }
    val ongoing = projects.count { it.projectStatus() == ProjectStatus.Ongoing }
    val completed = projects.count { it.projectStatus() == ProjectStatus.Completed }
    val active = projects.filter { StatusRules.isActive(it, today) }
    val updatedCodes = todayEntries.map { it.drrCode }.toSet()
    val updatedToday = active.count { it.drrCode in updatedCodes }
    val byCode = entries.groupBy { it.drrCode }
    val needUpdate = projects.filter { it.projectStatus() == ProjectStatus.Pending }
        .sortedWith(compareBy({ it.subBlock }, { it.drrCode }))
    val reminderOn = ReminderPrefs.enabled(ctx)
    val notifBlocked = reminderOn && !ReminderHealth.notificationsAllowed(ctx)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Good day, TM", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("Block $block", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(today.format(dayFormat), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text("B$block", Modifier.padding(horizontal = 15.dp, vertical = 11.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }

        if (!AppTime.isWorkingDay(today)) {
            MessageBanner(BannerKind.Info, "Today is a non-working day (Friday / Saturday). No update is required and no reminder is sent.")
        }

        // Search entry: opens the project list
        Surface(
            onClick = { go("projects") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text("Search a project by DRR-Code", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Today's workforce
        Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.primary, tonalElevation = 2.dp) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Today's workforce", color = Color.White.copy(alpha = .78f), style = MaterialTheme.typography.labelLarge)
                        Text("$totalWorkers workers", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    Text("$updatedToday / ${active.size} updated", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                LinearProgressIndicator(
                    progress = { if (active.isEmpty()) 0f else (updatedToday.toFloat() / active.size).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = .22f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    WorkforceMetric("Skilled", skilled)
                    WorkforceMetric("Unskilled", unskilled)
                }
            }
        }

        // Status summary cards
        SectionTitle("Project status") {
            Text("${projects.size} total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatusCard(ProjectStatus.Pending, pending, Modifier.weight(1f)) { go("projects:Pending") }
            StatusCard(ProjectStatus.Ongoing, ongoing, Modifier.weight(1f)) { go("projects:Ongoing") }
            StatusCard(ProjectStatus.Completed, completed, Modifier.weight(1f)) { go("projects:Completed") }
        }

        // Reminder
        AppCard(Modifier.fillMaxWidth(), onClick = { go("reminder") }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Daily reminder", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        if (reminderOn) "On - 9:30 AM, Sunday to Thursday" else "Off - tap to turn on",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (notifBlocked) {
                        Text("Notifications are blocked - tap to fix", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // Needs attention
        SectionTitle("Needs today's update")
        when {
            loading -> LoadingBox(label = "Loading projects...")
            projects.isEmpty() -> EmptyState(
                icon = Icons.Filled.Add,
                title = "No projects yet",
                body = "Register your first project to start tracking daily workforce and progress.",
                actionLabel = "Register project",
                onAction = { go("register") }
            )
            needUpdate.isEmpty() -> MessageBanner(
                BannerKind.Success,
                if (active.isEmpty()) "No active project. Completed projects are listed under Projects."
                else "All active projects are up to date."
            )
            else -> {
                needUpdate.take(5).forEach { p ->
                    ProjectCard(p, byCode[p.drrCode].orEmpty(), today) { go("project:${p.drrCode}") }
                }
                if (needUpdate.size > 5) {
                    TextButton(onClick = { go("projects:Pending") }, modifier = Modifier.fillMaxWidth()) {
                        Text("See all ${needUpdate.size} pending projects")
                    }
                }
            }
        }
        if (projects.isNotEmpty()) {
            OutlinedButton(onClick = { go("projects") }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
                Text("View all ${projects.size} projects")
            }
        }

        // Quick actions
        SectionTitle("Quick actions")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            ActionCard(Icons.Filled.Edit, "Daily update", "Record today", { go("update") }, Modifier.weight(1f))
            ActionCard(Icons.Filled.Add, "Register", "New project", { go("register") }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            ActionCard(Icons.AutoMirrored.Filled.List, "Summary", "View progress", { go("summary") }, Modifier.weight(1f))
            ActionCard(Icons.Filled.Refresh, "Kobo sync", "Send / restore", { go("sync") }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            ActionCard(Icons.Filled.Email, "Project update", "Daily message", { go("projectupdate") }, Modifier.weight(1f))
            ActionCard(Icons.AutoMirrored.Filled.List, "WhatsApp report", "Block totals", { go("report") }, Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun WorkforceMetric(label: String, value: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(8.dp).background(Color.White, RoundedCornerShape(50)))
        Text("$value $label", color = Color.White.copy(alpha = .9f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusCard(status: ProjectStatus, count: Int, modifier: Modifier, onClick: () -> Unit) {
    val (bg, fg) = StatusStyle.colors(status)
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp), color = bg) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(count.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = fg)
            Text(status.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = fg)
        }
    }
}

@Composable
private fun ActionCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun RegisterProjectScreen(block: String, existing: Map<String, Project>, onSave: (Project) -> Unit) {
    var code by remember { mutableStateOf("") }
    var activity by remember { mutableStateOf("") }
    var subBlock by remember { mutableStateOf("") }
    var start by remember { mutableStateOf(AppTime.today().toString()) }
    var error by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column {
            Text("New project", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Block $block. A new project is Pending until its first daily update is saved.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AppCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    code, { code = it.trim(); error = "" }, label = { Text("DRR-CODE") },
                    supportingText = { Text("Each project needs its own DRR-CODE") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    activity, { activity = it; error = "" }, label = { Text("Activity type") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    subBlock, { subBlock = it.trim().uppercase(); error = "" }, label = { Text("Sub-block (e.g. A06)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    start, { start = it.trim(); error = "" }, label = { Text("Start date (yyyy-MM-dd)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (error.isNotEmpty()) MessageBanner(BannerKind.Error, error)
        Button(
            onClick = {
                val ok = code.isNotEmpty() && activity.isNotBlank() && subBlock.isNotEmpty() && runCatching { LocalDate.parse(start) }.isSuccess
                val dup = existing[code]
                if (!ok) error = "Please fill DRR-CODE, activity, sub-block and a correct start date."
                else if (dup != null) error = "DRR-CODE $code is already registered (${dup.subBlock} - ${dup.activity}). Each project needs its own DRR-CODE."
                else onSave(Project(code, activity.trim(), block, subBlock, start, status = ProjectStatus.Pending.label))
            },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp)
        ) { Text("Save project", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
    }
}
