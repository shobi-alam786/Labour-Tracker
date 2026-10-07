@file:OptIn(ExperimentalMaterial3Api::class)

package com.shobi.labourtracker

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate

// Admin: sees every block. Data comes from Kobo ("Download all blocks").
@Composable
fun AdminApp(dao: AppDao, onLogout: () -> Unit) {
    val projects by dao.allProjects().collectAsState(emptyList())
    val updates by dao.allUpdates().collectAsState(emptyList())
    val byCode = remember(projects) { projects.associateBy { it.drrCode } }
    val entries = remember(projects, updates) {
        updates.mapNotNull { u -> byCode[u.drrCode]?.let { u.toEntry(it) } }
    }
    var openBlock by remember { mutableStateOf<String?>(null) }
    var openProject by remember { mutableStateOf<String?>(null) }
    var showReport by remember { mutableStateOf(false) }

    BackHandler(enabled = openProject != null || showReport || openBlock != null) {
        when {
            openProject != null -> openProject = null
            showReport -> showReport = false
            else -> openBlock = null
        }
    }

    val proj = openProject?.let { byCode[it] }
    val blk = openBlock
    when {
        proj != null -> ProjectDetailScreen(proj, entries, null)
        showReport -> AdminReportScreen(entries)
        blk != null -> SummaryScreen(
            "Block $blk", projects.filter { it.block == blk }.sortedBy { it.subBlock }, entries
        ) { openProject = it }
        else -> AdminHome(dao, projects, entries, { openBlock = it }, { showReport = true }, onLogout)
    }
}

@Composable
private fun AdminHome(
    dao: AppDao,
    projects: List<Project>,
    entries: List<DailyEntry>,
    onOpenBlock: (String) -> Unit,
    onReport: () -> Unit,
    onLogout: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val todayT = Summary.total(entries.filter { it.date == today })
    val allT = Summary.total(entries)
    val w = listOf(1f, 1.3f, 1.2f, 1.5f, 1.1f)

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text("Admin", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("All blocks", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(
            onClick = { busy = true; scope.launch { msg = pullData(dao, null); busy = false } },
            enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(if (busy) "Downloading..." else "Sync: download all blocks from Kobo") }
        if (msg.isNotEmpty()) Text(msg, Modifier.padding(horizontal = 6.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("Today", (todayT.skilled + todayT.unskilled).toString(), Modifier.weight(1f))
            Metric("All labour", (allT.skilled + allT.unskilled).toString(), Modifier.weight(1f))
            Metric("Projects", projects.size.toString(), Modifier.weight(1f))
        }

        TableCard("▦", "Block Summary") {
            TableRow(listOf("Block", "Projects", "Skilled", "Unskilled", "Total"), w, header = true)
            "ABCDEFG".map { it.toString() }.forEach { b ->
                val t = Summary.total(entries.filter { it.block == b })
                TableRow(
                    listOf(b, projects.count { it.block == b }.toString(), t.skilled.toString(), t.unskilled.toString(), (t.skilled + t.unskilled).toString()),
                    w, onClick = { onOpenBlock(b) }
                )
            }
            TableRow(
                listOf("All", projects.size.toString(), allT.skilled.toString(), allT.unskilled.toString(), (allT.skilled + allT.unskilled).toString()),
                w, bold = true
            )
        }
        Text(
            "Tap a block to see its projects, then a project to see labour from start to end.",
            Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onReport, Modifier.fillMaxWidth()) { Text("All-blocks WhatsApp report") }
        OutlinedButton(onLogout, Modifier.fillMaxWidth()) { Text("Log out") }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AdminReportScreen(entries: List<DailyEntry>) {
    val ctx = LocalContext.current
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    val d = runCatching { LocalDate.parse(date) }.getOrNull()
    val text = if (d != null) WhatsAppReport.allBlocksReport(d, entries) else "Wrong date"
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("All Blocks Report", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(date, { date = it }, label = { Text("Report date (yyyy-MM-dd)") }, modifier = Modifier.fillMaxWidth())
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Button({ shareToWhatsApp(ctx, text) }, Modifier.fillMaxWidth(), enabled = d != null) { Text("Send on WhatsApp") }
    }
}
