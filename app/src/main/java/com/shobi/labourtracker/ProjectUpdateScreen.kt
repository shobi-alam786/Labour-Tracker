@file:OptIn(ExperimentalMaterial3Api::class)

package com.shobi.labourtracker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// blocks: one block for a TM, A..G for Admin (then a block chooser is shown)
@Composable
fun ProjectUpdateScreen(blocks: List<String>, projects: List<Project>, entries: List<DailyEntry>) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var block by remember { mutableStateOf(blocks.first()) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var dateMenu by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val dateOptions = remember { (0..30).map { LocalDate.now().minusDays(it.toLong()) } }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEE, dd MMM yyyy", Locale.ENGLISH) }
    val text = remember(block, date, projects, entries) {
        ProjectUpdateMessage.build(date, projects.filter { it.block == block }, entries)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Daily Project Update", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (blocks.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                blocks.forEach { b ->
                    FilterChip(selected = b == block, onClick = { block = b }, label = { Text(b) })
                }
            }
        } else {
            Text("Block $block", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            OutlinedButton({ dateMenu = true }, Modifier.fillMaxWidth()) {
                Text("Date: ${date.format(dateFormat)}  ⌄")
            }
            DropdownMenu(expanded = dateMenu, onDismissRequest = { dateMenu = false }) {
                dateOptions.forEach { d ->
                    DropdownMenuItem(text = { Text(d.format(dateFormat)) }, onClick = { date = d; dateMenu = false; copied = false })
                }
            }
        }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 1.dp) {
            Text(text, Modifier.padding(16.dp).fillMaxWidth(), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
        Button({ shareToWhatsApp(ctx, text) }, Modifier.fillMaxWidth()) { Text("Send on WhatsApp") }
        OutlinedButton({ clipboard.setText(AnnotatedString(text)); copied = true }, Modifier.fillMaxWidth()) {
            Text(if (copied) "Copied" else "Copy text")
        }
        Spacer(Modifier.height(8.dp))
    }
}
