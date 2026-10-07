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
import org.json.JSONObject

// EDIT THESE LINES for your Kobo account and forms
object KoboConfig {
    const val SERVER = "https://kc.kobotoolbox.org"
    const val TOKEN = "df36f81bf71b0039ef6a8f3a248bca94322567b6"
    // ONE combined form (projects + daily updates). Paste its UID from the Kobo project URL.
    const val FORM_UID = "PASTE_NEW_FORM_UID"
    const val KF_SERVER = "https://kf.kobotoolbox.org"
    // Admin login PIN. CHANGE THIS before you build the release APK.
    const val ADMIN_PIN = "7391"
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
            val sent = post(xml(KoboConfig.FORM_UID, linkedMapOf(
                "record_type" to "project",
                "drr_code" to p.drrCode, "activity" to p.activity, "block" to p.block,
                "sub_block" to p.subBlock, "start_date" to p.startDate, "end_date" to p.endDate)))
            if (sent) { dao.markProjectSynced(p.drrCode); ok++ } else fail++
        }
        dao.unsyncedUpdates().forEach { u ->
            val blk = dao.getProject(u.drrCode)?.block ?: block
            val sent = post(xml(KoboConfig.FORM_UID, linkedMapOf(
                "record_type" to "update",
                "drr_code" to u.drrCode, "block" to blk, "date" to u.date, "status" to u.status,
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
    Column(
        Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Sync with Kobo", style = MaterialTheme.typography.titleLarge)
        Button(
            onClick = { busy = true; scope.launch { msg = syncAll(dao, block); busy = false } },
            enabled = !busy, modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Working..." else "Send now") }
        Text("Sends your new projects and daily updates to Kobo.", style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        OutlinedButton(
            onClick = { busy = true; scope.launch { msg = pullData(dao, block); busy = false } },
            enabled = !busy, modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Working..." else "Restore my Block $block data") }
        Text(
            "Lost or changed your phone? Pick your block, then restore all your projects and daily updates from Kobo. Data not yet sent from this phone is never overwritten.",
            style = MaterialTheme.typography.bodySmall
        )
        HorizontalDivider()
        OutlinedButton(
            onClick = { busy = true; scope.launch { dao.markAllUnsynced(); msg = syncAll(dao, block); busy = false } },
            enabled = !busy, modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Working..." else "Re-send all my data") }
        Text(
            "Use once after changing to the new combined Kobo form, so old data also goes to the new form.",
            style = MaterialTheme.typography.bodySmall
        )
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

// All submissions of the combined form, oldest first (so the newest one wins when saved in order)
private fun fetchAll(): List<JSONObject> {
    val out = ArrayList<JSONObject>()
    var url: String? = "${KoboConfig.KF_SERVER}/api/v2/assets/${KoboConfig.FORM_UID}/data/?format=json&limit=1000"
    while (url != null) {
        val j = JSONObject(get(url))
        val arr = j.getJSONArray("results")
        for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
        url = if (j.isNull("next")) null else j.optString("next").replace("http://", "https://").ifEmpty { null }
    }
    return out.sortedBy { it.optLong("_id") }
}

// Download from Kobo into this phone.
// block = null -> everything (Admin).  block = "B" -> only that TM's block (restore after losing a phone).
suspend fun pullData(dao: AppDao, block: String?): String = withContext(Dispatchers.IO) {
    var np = 0
    var nu = 0
    var kept = 0
    try {
        val all = fetchAll()
        val codes = HashSet<String>()
        for (o in all.filter { it.optString("record_type") == "project" }) {
            val code = o.optString("drr_code")
            val blk = o.optString("block")
            if (code.isEmpty() || (block != null && blk != block)) continue
            codes.add(code)
            val local = dao.getProject(code)
            if (local != null && !local.synced) { kept++; continue }
            dao.saveProject(Project(code, o.optString("activity"), blk, o.optString("sub_block"),
                o.optString("start_date"), o.optString("end_date"),
                status = local?.status ?: "Ongoing", progress = local?.progress ?: 0, synced = true))
            np++
        }
        for (o in all.filter { it.optString("record_type") == "update" }) {
            val code = o.optString("drr_code")
            val date = o.optString("date")
            if (code.isEmpty() || date.isEmpty()) continue
            if (block != null && o.optString("block") != block && code !in codes) continue
            val local = dao.getUpdate(code, date)
            if (local != null && !local.synced) { kept++; continue }
            dao.saveUpdate(DailyUpdate(code, date, o.optString("status").ifEmpty { "Ongoing" },
                o.optString("progress").toIntOrNull() ?: 0,
                o.optString("skilled").toIntOrNull() ?: 0,
                o.optString("unskilled").toIntOrNull() ?: 0, synced = true))
            nu++
        }
    } catch (e: Exception) {
        return@withContext "No internet or error: ${e.message}"
    }
    "Downloaded: $np projects, $nu daily updates" + if (kept > 0) "  (kept $kept unsent items on this phone)" else ""
}
