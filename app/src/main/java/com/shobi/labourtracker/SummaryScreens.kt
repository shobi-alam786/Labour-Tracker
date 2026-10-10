@file:OptIn(ExperimentalMaterial3Api::class)

package com.shobi.labourtracker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HeaderBg = Color(0xFFE8ECF6)
private val HeaderText = Color(0xFF454B5E)
private val RowLine = Color(0xFFE6E8EE)
private val shortDate = DateTimeFormatter.ofPattern("dd MMM yy", Locale.ENGLISH)

// ---------- Numbers per project (start to now) ----------
data class ProjectStat(
    val project: Project,
    val skilled: Int,
    val unskilled: Int,
    val days: Int,
    val progress: Int
) {
    val total get() = skilled + unskilled
}

fun projectStats(projects: List<Project>, entries: List<DailyEntry>): List<ProjectStat> {
    val by = entries.groupBy { it.drrCode }
    return projects.map { p ->
        val e = by[p.drrCode].orEmpty()
        val last = e.maxByOrNull { it.date }
        ProjectStat(
            p, e.sumOf { it.skilled }, e.sumOf { it.unskilled },
            e.map { it.date }.distinct().size,
            (last?.progress ?: p.progress).coerceIn(0, 100)
        )
    }
}

// ---------- Table pieces (same look as the Submission Details screen) ----------
@Composable
fun TableCard(icon: String, title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = Color.White) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.padding(start = 6.dp, top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
fun TableRow(
    cells: List<String>,
    weights: List<Float>,
    header: Boolean = false,
    bold: Boolean = false,
    sub: String? = null,
    onClick: (() -> Unit)? = null
) {
    Column {
        var m = Modifier.fillMaxWidth()
        if (header) m = m.background(HeaderBg, RoundedCornerShape(14.dp))
        if (onClick != null) m = m.clickable(onClick = onClick)
        Row(
            m.padding(horizontal = 12.dp, vertical = if (header) 12.dp else 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            cells.forEachIndexed { i, t ->
                if (i == 0 && sub != null) {
                    Column(Modifier.weight(weights[i])) {
                        Text(t, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            sub, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Text(
                        t, Modifier.weight(weights[i]),
                        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyLarge,
                        fontWeight = if (header) FontWeight.SemiBold else if (bold) FontWeight.Bold else FontWeight.Normal,
                        color = if (header) HeaderText else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        }
        if (!header) HorizontalDivider(color = RowLine)
    }
}

@Composable
fun Metric(title: String, value: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(18.dp), color = Color.White) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

// ---------- Summary: one row per project, tap to open ----------
@Composable
fun SummaryScreen(
    title: String,
    projects: List<Project>,
    entries: List<DailyEntry>,
    onOpenProject: (String) -> Unit
) {
    val stats = remember(projects, entries) { projectStats(projects, entries) }
    val w = listOf(2.2f, 1.2f, 1.5f, 1.1f)
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Tap a project to see labour from start to end",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("Projects", stats.size.toString(), Modifier.weight(1f))
            Metric("Active", stats.count { it.project.projectStatus() != ProjectStatus.Completed }.toString(), Modifier.weight(1f))
            Metric("Labour", stats.sumOf { it.total }.toString(), Modifier.weight(1f))
        }
        TableCard("▦", "Project Summary") {
            TableRow(listOf("Project", "Skilled", "Unskilled", "Total"), w, header = true)
            if (stats.isEmpty()) {
                Text(
                    "No projects yet.", Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            stats.forEach { s ->
                TableRow(
                    listOf(s.project.subBlock.ifBlank { "-" }, s.skilled.toString(), s.unskilled.toString(), s.total.toString()),
                    w, sub = "${s.project.activity} • ${s.progress}% • ${s.project.projectStatus().label}",
                    onClick = { onOpenProject(s.project.drrCode) }
                )
            }
            if (stats.isNotEmpty()) {
                TableRow(
                    listOf("Total", stats.sumOf { it.skilled }.toString(), stats.sumOf { it.unskilled }.toString(), stats.sumOf { it.total }.toString()),
                    w, bold = true
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------- One project: labour from start to end ----------
// onEdit = null means read-only (Admin)
@Composable
fun ProjectDetailScreen(
    project: Project,
    allEntries: List<DailyEntry>,
    onEdit: ((DailyUpdate) -> Unit)?,
    onMarkCompleted: (() -> Unit)? = null,   // null = read-only (Admin)
    onReopen: (() -> Unit)? = null
) {
    val entries = remember(project, allEntries) {
        allEntries.filter { it.drrCode == project.drrCode }.sortedBy { it.date }
    }
    val stat = projectStats(listOf(project), entries).first()
    val days = stat.days.coerceAtLeast(1)
    fun avg(n: Int) = String.format(Locale.ENGLISH, "%.1f", n.toFloat() / days)
    var editing by remember { mutableStateOf<DailyEntry?>(null) }
    var confirmComplete by remember { mutableStateOf(false) }
    var confirmReopen by remember { mutableStateOf(false) }
    val status = project.projectStatus()   // the SAVED status, same value as on the dashboard and list

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text("Project Details", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "${project.subBlock} • ${project.activity}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        TableCard("ⓘ", "Project Info") {
            Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoLine("DRR-CODE", project.drrCode)
                InfoLine("Block / Sub-block", "${project.block} / ${project.subBlock}")
                InfoLine("Start date", project.startDate)
                InfoLine("End date", project.endDate.ifBlank { "Not set" })
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Status", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    StatusChip(status)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${stat.progress}%", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    LinearProgressIndicator(
                        progress = { stat.progress / 100f },
                        modifier = Modifier.weight(1f).height(8.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        if (onMarkCompleted != null && status != ProjectStatus.Completed) {
            if (stat.progress >= 100) {
                MessageBanner(BannerKind.Info, "The latest update shows 100%. The project stays ${status.label} until you mark it completed.")
            }
            Button(
                onClick = { confirmComplete = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) { Text("Mark project as completed", fontWeight = FontWeight.Bold) }
        }
        if (onReopen != null && status == ProjectStatus.Completed) {
            OutlinedButton(
                onClick = { confirmReopen = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) { Text("Reopen project", fontWeight = FontWeight.Bold) }
        }

        val w1 = listOf(1.6f, 1f, 1f)
        TableCard("▥", "Labour (Start to End)") {
            TableRow(listOf("Labour", "Total", "Avg / day"), w1, header = true)
            TableRow(listOf("Skilled", stat.skilled.toString(), avg(stat.skilled)), w1)
            TableRow(listOf("Unskilled", stat.unskilled.toString(), avg(stat.unskilled)), w1)
            TableRow(listOf("Total", stat.total.toString(), avg(stat.total)), w1, bold = true)
            TableRow(listOf("Days worked", stat.days.toString(), ""), w1)
        }

        val w2 = listOf(1.7f, 1.1f, 1.4f, 1.1f)
        TableCard("▤", "Daily Record") {
            TableRow(listOf("Date", "Skilled", "Unskilled", "Progress"), w2, header = true)
            if (entries.isEmpty()) {
                Text("No daily update yet.", Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            entries.forEach { e ->
                TableRow(
                    listOf(e.date.format(shortDate), e.skilled.toString(), e.unskilled.toString(), "${e.progress}%"),
                    w2, onClick = if (onEdit != null) ({ editing = e }) else null
                )
            }
            if (onEdit != null && entries.isNotEmpty()) {
                Text(
                    "Tap a row to edit that day.", Modifier.padding(12.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    editing?.let { entry ->
        EditDayDialog(entry, { editing = null }) { u -> onEdit?.invoke(u); editing = null }
    }
    if (confirmComplete) {
        AlertDialog(
            onDismissRequest = { confirmComplete = false },
            title = { Text("Mark as completed?", fontWeight = FontWeight.Bold) },
            text = { Text("${project.subBlock} - ${project.activity} (DRR ${project.drrCode}) will be Completed. The daily reminder will no longer check it.") },
            confirmButton = { Button({ confirmComplete = false; onMarkCompleted?.invoke() }) { Text("Mark completed") } },
            dismissButton = { TextButton({ confirmComplete = false }) { Text("Cancel") } }
        )
    }
    if (confirmReopen) {
        AlertDialog(
            onDismissRequest = { confirmReopen = false },
            title = { Text("Reopen project?", fontWeight = FontWeight.Bold) },
            text = { Text("The project becomes active again and is checked by the daily reminder.") },
            confirmButton = { Button({ confirmReopen = false; onReopen?.invoke() }) { Text("Reopen") } },
            dismissButton = { TextButton({ confirmReopen = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EditDayDialog(entry: DailyEntry, onDismiss: () -> Unit, onSave: (DailyUpdate) -> Unit) {
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
