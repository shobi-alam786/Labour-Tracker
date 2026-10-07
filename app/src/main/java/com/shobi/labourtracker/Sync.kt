package com.shobi.labourtracker

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.time.LocalDate

// EDIT THESE LINES for your Kobo account and forms
object KoboConfig {
    const val SERVER = "https://kc.kobotoolbox.org"
    const val TOKEN = "df36f81bf71b0039ef6a8f3a248bca94322567b6"
    const val PROJECT_FORM_ID = "azCVgFYadahedgDHVwR5xV"
    const val UPDATE_FORM_ID = "adBm3wtzUQP3gVWFjcZjPR"
    // For admin pull: server and the asset UID of each form (from the Kobo project URL)
    const val KF_SERVER = "https://kf.kobotoolbox.org"
    const val PROJECT_ASSET_UID = "azCVgFYadahedgDHVwR5xV"
    const val UPDATE_ASSET_UID = "adBm3wtzUQP3gVWFjcZjPR"
}

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun xml(formId: String, f: Map<String, String>): String {
    val body = f.entries.joinToString("") { "<${it.key}>${esc(it.value)}</${it.key}>" }
    return "<$formId id=\"$formId\">$body<meta><instanceID>uuid:${UUID.randomUUID()}</instanceID></meta></$formId>"
}

private fun post(xml: String): Boolean {
    val boundary = "----kobo" + System.currentTimeMillis()
    val c = URL("${KoboConfig.SERVER}/submission").openConnection() as HttpURLConnection
    c.requestMethod = "POST"
    c.doOutput = true
    c.connectTimeout = 15000
    c.readTimeout = 20000
    c.setRequestProperty("Authorization", "Token ${KoboConfig.TOKEN}")
    c.setRequestProperty("X-OpenRosa-Version", "1.0")
    c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
    c.outputStream.use {
        val s = "--$boundary\r\nContent-Disposition: form-data; name=\"xml_submission_file\"; " +
            "filename=\"s.xml\"\r\nContent-Type: text/xml\r\n\r\n$xml\r\n--$boundary--\r\n"
        it.write(s.toByteArray())
    }
    val code = c.responseCode
    val body = (if (code >= 400) c.errorStream else c.inputStream)
        ?.bufferedReader()?.use { it.readText() }?.take(200) ?: ""
    c.disconnect()
    if (code != 201 && code != 202) throw Exception("HTTP $code $body")
    return true
}

suspend fun syncAll(dao: AppDao, block: String): String = withContext(Dispatchers.IO) {
    var ok = 0
    var fail = 0
    try {
        dao.unsyncedProjects().forEach { p ->
            val sent = post(xml(KoboConfig.PROJECT_FORM_ID, linkedMapOf(
                "drr_code" to p.drrCode, "activity" to p.activity, "block" to p.block,
                "sub_block" to p.subBlock, "start_date" to p.startDate, "end_date" to p.endDate)))
            if (sent) { dao.markProjectSynced(p.drrCode); ok++ } else fail++
        }
        dao.unsyncedUpdates().forEach { u ->
            val sent = post(xml(KoboConfig.UPDATE_FORM_ID, linkedMapOf(
                "drr_code" to u.drrCode, "block" to block, "date" to u.date, "status" to u.status,
                "progress" to u.progress.toString(), "skilled" to u.skilled.toString(),
                "unskilled" to u.unskilled.toString())))
            if (sent) { dao.markUpdateSynced(u.drrCode, u.date); ok++ } else fail++
        }
    } catch (e: Exception) {
        return@withContext "No internet or error: ${e.message}"
    }
    "Sent: $ok   Failed: $fail"
}

@Composable
fun SyncScreen(block: String, dao: AppDao) {
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Sync to Kobo", style = MaterialTheme.typography.titleLarge)
        Button(
            onClick = { busy = true; scope.launch { msg = syncAll(dao, block); busy = false } },
            enabled = !busy, modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Sending..." else "Send now") }
        Text(msg)
    }
}

private fun get(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.setRequestProperty("Authorization", "Token ${KoboConfig.TOKEN}")
    c.connectTimeout = 15000
    c.readTimeout = 30000
    val t = c.inputStream.bufferedReader().use { it.readText() }
    c.disconnect()
    return t
}

// Admin: download all submissions from Kobo into the local database
suspend fun pullAll(dao: AppDao): String = withContext(Dispatchers.IO) {
    var np = 0
    var nu = 0
    try {
        val base = "${KoboConfig.KF_SERVER}/api/v2/assets"
        val ps = JSONObject(get("$base/${KoboConfig.PROJECT_ASSET_UID}/data/?format=json&limit=30000")).getJSONArray("results")
        for (i in 0 until ps.length()) {
            val o = ps.getJSONObject(i)
            val code = o.optString("drr_code")
            if (code.isEmpty()) continue
            dao.saveProject(Project(code, o.optString("activity"), o.optString("block"),
                o.optString("sub_block"), o.optString("start_date"), o.optString("end_date"), synced = true))
            np++
        }
        val us = JSONObject(get("$base/${KoboConfig.UPDATE_ASSET_UID}/data/?format=json&limit=30000")).getJSONArray("results")
        for (i in 0 until us.length()) {
            val o = us.getJSONObject(i)
            val code = o.optString("drr_code")
            val date = o.optString("date")
            if (code.isEmpty() || date.isEmpty()) continue
            dao.saveUpdate(DailyUpdate(code, date, o.optString("status"),
                o.optString("progress").toIntOrNull() ?: 0,
                o.optString("skilled").toIntOrNull() ?: 0,
                o.optString("unskilled").toIntOrNull() ?: 0, synced = true))
            nu++
        }
    } catch (e: Exception) {
        return@withContext "No internet or error: ${e.message}"
    }
    "Downloaded: $np projects, $nu updates"
}

@Composable
fun AdminScreen(dao: AppDao) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val projects by dao.allProjects().collectAsState(emptyList())
    val updates by dao.allUpdates().collectAsState(emptyList())
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    val byCode = projects.associateBy { it.drrCode }
    val entries = updates.mapNotNull { u -> byCode[u.drrCode]?.let { u.toEntry(it) } }
    val d = runCatching { LocalDate.parse(date) }.getOrNull()
    val text = if (d != null) WhatsAppReport.allBlocksReport(d, entries) else "Wrong date"
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("All Blocks (Admin)", style = MaterialTheme.typography.titleLarge)
        Button(
            onClick = { busy = true; scope.launch { msg = pullAll(dao); busy = false } },
            enabled = !busy, modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Downloading..." else "Download all data from Kobo") }
        Text(msg)
        OutlinedTextField(date, { date = it }, label = { Text("Report date (yyyy-MM-dd)") }, modifier = Modifier.fillMaxWidth())
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Button({ shareToWhatsApp(ctx, text) }, Modifier.fillMaxWidth(), enabled = d != null) { Text("Send on WhatsApp") }
    }
}
