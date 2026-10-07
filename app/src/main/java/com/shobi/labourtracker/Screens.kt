package com.shobi.labourtracker

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
fun MenuScreen(block: String, go: (String) -> Unit) {
    val items = listOf(
        "Register Project" to "register",   // first option
        "Daily Update" to "update",
        "Summary" to "summary",
        "WhatsApp Report" to "report",
        "Sync to Kobo" to "sync",
        "All Blocks (Admin)" to "admin"
    )
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Block $block", style = MaterialTheme.typography.headlineSmall)
        items.forEach { (label, route) ->
            Button(onClick = { go(route) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
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

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Register Project - Block $block", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(code, { code = it.trim() }, label = { Text("DRR-CODE") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(activity, { activity = it }, label = { Text("Activity type") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(subBlock, { subBlock = it.trim().uppercase() }, label = { Text("Sub-block (e.g. A06)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(start, { start = it }, label = { Text("Start date (yyyy-MM-dd)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(end, { end = it }, label = { Text("End date (optional)") }, modifier = Modifier.fillMaxWidth())
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(
            onClick = {
                val ok = code.isNotEmpty() && activity.isNotBlank() && subBlock.isNotEmpty() &&
                    runCatching { LocalDate.parse(start) }.isSuccess
                if (!ok) error = "Please fill DRR-CODE, activity, sub-block and a correct start date."
                else onSave(Project(code, activity.trim(), block, subBlock, start, end))
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save Project") }
    }
}
