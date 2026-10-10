@file:OptIn(ExperimentalMaterial3Api::class)

package com.shobi.labourtracker

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

enum class StatusFilter(val label: String) {
    All("All"), Pending("Pending"), Ongoing("Ongoing"), Completed("Completed");

    fun matches(s: ProjectStatus): Boolean = this == All || this.name == s.name
}

// Search: DRR-Code first, plus sub-block and activity as a convenience. Pending projects are listed first.
fun filterProjects(projects: List<Project>, filter: StatusFilter, query: String): List<Project> {
    val q = query.trim()
    return projects.filter { p ->
        filter.matches(p.projectStatus()) && (
            q.isEmpty() ||
                p.drrCode.contains(q, ignoreCase = true) ||
                p.subBlock.contains(q, ignoreCase = true) ||
                p.activity.contains(q, ignoreCase = true)
            )
    }.sortedWith(compareBy({ it.projectStatus().ordinal }, { it.subBlock }, { it.drrCode }))
}

@Composable
fun ProjectsScreen(
    projects: List<Project>,
    entries: List<DailyEntry>,
    loading: Boolean,
    initialFilter: StatusFilter,
    onOpen: (String) -> Unit,
    onRegister: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by remember(initialFilter) { mutableStateOf(initialFilter) }
    val byCode = remember(entries) { entries.groupBy { it.drrCode } }
    val today = AppTime.today()
    val shown = remember(projects, filter, query) { filterProjects(projects, filter, query) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                placeholder = { Text("Search DRR-Code, sub-block or activity") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusFilter.values().forEach { f ->
                    val n = projects.count { f.matches(it.projectStatus()) }
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text("${f.label}  $n") }
                    )
                }
            }
        }

        when {
            loading -> LoadingBox(label = "Loading projects...")
            projects.isEmpty() -> EmptyState(
                icon = Icons.Filled.Add,
                title = "No projects yet",
                body = "Register your first project to start tracking daily workforce and progress.",
                actionLabel = "Register project",
                onAction = onRegister
            )
            shown.isEmpty() -> EmptyState(
                icon = Icons.Filled.Search,
                title = "No matching project",
                body = if (query.isNotBlank()) "Nothing matches \"${query.trim()}\" in ${filter.label.lowercase()} projects."
                else "There is no ${filter.label.lowercase()} project.",
                actionLabel = if (query.isNotBlank() || filter != StatusFilter.All) "Show all projects" else null,
                onAction = { query = ""; filter = StatusFilter.All }
            )
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        "Showing ${shown.size} of ${projects.size} projects",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(shown, key = { it.drrCode }) { p ->
                    ProjectCard(p, byCode[p.drrCode].orEmpty(), today) { onOpen(p.drrCode) }
                }
            }
        }
    }
}

// One project: sub-block + activity, saved status, progress, today's labour.
@Composable
fun ProjectCard(project: Project, entries: List<DailyEntry>, today: java.time.LocalDate, onClick: () -> Unit) {
    val last = entries.maxByOrNull { it.date }
    val progress = (last?.progress ?: project.progress).coerceIn(0, 100)
    val status = project.projectStatus()
    val todayEntry = entries.firstOrNull { it.date == today }
    AppCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(project.subBlock.ifBlank { "-" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(project.activity, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusChip(status)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$progress%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.weight(1f).height(7.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("DRR ${project.drrCode}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val note = when {
                todayEntry != null -> "Today: ${todayEntry.skilled + todayEntry.unskilled} workers"
                status == ProjectStatus.Completed -> "Completed"
                else -> "No update today"
            }
            Text(
                note,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (todayEntry == null && status != ProjectStatus.Completed) FontWeight.Bold else FontWeight.Normal,
                color = if (todayEntry == null && status == ProjectStatus.Pending) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
