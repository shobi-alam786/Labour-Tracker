@file:OptIn(ExperimentalMaterial3Api::class)

package com.shobi.labourtracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
fun DashboardScreen(
    block: String,
    projects: List<Project>,
    entries: List<DailyEntry>,
    go: (String) -> Unit,
    today: LocalDate = LocalDate.now()
) {
    val todayEntries = entries.filter { it.date == today }
    val skilled = todayEntries.sumOf { it.skilled }
    val unskilled = todayEntries.sumOf { it.unskilled }
    val activeProjects = projects.count { it.status.equals("Ongoing", true) }
    val completedProjects = projects.count { it.status.equals("Completed", true) || it.progress >= 100 }
    val totalWorkers = skilled + unskilled

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Good day, TM", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text("Block $block", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(today.toString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text("B$block", modifier = Modifier.padding(horizontal = 15.dp, vertical = 11.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }

        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.primary,
            tonalElevation = 2.dp
        ) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Today's workforce", color = Color.White.copy(alpha = .78f), style = MaterialTheme.typography.labelLarge)
                        Text("$totalWorkers workers", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    Text("${todayEntries.size} updates", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                LinearProgressIndicator(
                    progress = { if (projects.isEmpty()) 0f else (todayEntries.size.toFloat() / projects.size.coerceAtLeast(1)).coerceAtMost(1f) },
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

        Text("Overview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            StatCard("Projects", projects.size.toString(), "Total", Modifier.weight(1f))
            StatCard("Ongoing", activeProjects.toString(), "Active", Modifier.weight(1f))
            StatCard("Done", completedProjects.toString(), "Completed", Modifier.weight(1f))
        }

        Text("Quick actions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            ActionCard("＋", "Daily update", "Record today", { go("update") }, Modifier.weight(1f))
            ActionCard("▣", "Register", "New project", { go("register") }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            ActionCard("↗", "Summary", "View progress", { go("summary") }, Modifier.weight(1f))
            ActionCard("☁", "Kobo sync", "Send / restore", { go("sync") }, Modifier.weight(1f))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Projects", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${projects.size} total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (projects.isEmpty()) {
            EmptyProjectsCard { go("register") }
        } else {
            projects.take(8).forEach { project ->
                ProjectDashboardCard(project, entries.filter { it.drrCode == project.drrCode }) { go("project:${project.drrCode}") }
            }
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
private fun StatCard(title: String, value: String, caption: String, modifier: Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ActionCard(icon: String, title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier) {
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text(icon, modifier = Modifier.padding(10.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProjectDashboardCard(project: Project, entries: List<DailyEntry>, onClick: () -> Unit) {
    val last = entries.maxByOrNull { it.date }
    val progress = last?.let { projectProgress(it) } ?: project.progress
    val status = if (progress >= 100) "Completed" else "Ongoing"
    Surface(onClick = onClick, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(project.subBlock, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(project.activity, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = RoundedCornerShape(10.dp), color = if (status == "Completed") MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer) {
                    Text(status, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$progress%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                LinearProgressIndicator(progress = { progress.coerceIn(0, 100) / 100f }, modifier = Modifier.weight(1f).height(7.dp), trackColor = MaterialTheme.colorScheme.surfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("DRR ${project.drrCode}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (last != null) Text("${last.skilled + last.unskilled} workers", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun projectProgress(entry: DailyEntry): Int = entry.progress.coerceIn(0, 100)

@Composable
private fun EmptyProjectsCard(onRegister: () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("No projects yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Register your first project to start tracking daily workforce and progress.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onRegister, shape = RoundedCornerShape(14.dp)) { Text("Register project") }
        }
    }
}

@Composable
fun RegisterProjectScreen(block: String, onSave: (Project) -> Unit) {
    var code by remember { mutableStateOf("") }
    var activity by remember { mutableStateOf("") }
    var subBlock by remember { mutableStateOf("") }
    var start by remember { mutableStateOf(LocalDate.now().toString()) }
    var end by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Register Project - Block $block", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(code, { code = it.trim() }, label = { Text("DRR-CODE") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(activity, { activity = it }, label = { Text("Activity type") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(subBlock, { subBlock = it.trim().uppercase() }, label = { Text("Sub-block (e.g. A06)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(start, { start = it }, label = { Text("Start date (yyyy-MM-dd)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(end, { end = it }, label = { Text("End date (optional)") }, modifier = Modifier.fillMaxWidth())
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            val ok = code.isNotEmpty() && activity.isNotBlank() && subBlock.isNotEmpty() && runCatching { LocalDate.parse(start) }.isSuccess
            if (!ok) error = "Please fill DRR-CODE, activity, sub-block and a correct start date."
            else onSave(Project(code, activity.trim(), block, subBlock, start, end))
        }, modifier = Modifier.fillMaxWidth()) { Text("Save Project") }
    }
}
