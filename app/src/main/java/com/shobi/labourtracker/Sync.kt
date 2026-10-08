package com.shobi.labourtracker

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import org.json.JSONObject

// EDIT THESE LINES for your Kobo account and forms
object KoboConfig {
    const val SERVER = "https://kc.kobotoolbox.org"
    const val TOKEN = "df36f81bf71b0039ef6a8f3a248bca94322567b6"
    // ONE combined form (projects + daily updates). Paste its UID from the Kobo project URL.
    const val FORM_UID = "a6NyLaFx7XLYypjjW7JXyN"
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
    var firstError = ""
    var offline = false
    for (p in dao.unsyncedProjects()) {
        if (offline) break
        try {
            post(xml(KoboConfig.FORM_UID, linkedMapOf(
                "record_type" to "project",
                "drr_code" to p.drrCode, "activity" to p.activity, "block" to p.block,
                "sub_block" to p.subBlock, "start_date" to p.startDate, "end_date" to p.endDate)))
            dao.markProjectSynced(p.drrCode); ok++
        } catch (e: java.io.IOException) {
            offline = true; fail++; if (firstError.isEmpty()) firstError = "No internet (${e.message})"
        } catch (e: Exception) {
            fail++; if (firstError.isEmpty()) firstError = "Project ${p.drrCode}: ${e.message}"
        }
    }
    for (u in dao.unsyncedUpdates()) {
        if (offline) break
        try {
            val blk = dao.getProject(u.drrCode)?.block ?: block
            post(xml(KoboConfig.FORM_UID, linkedMapOf(
                "record_type" to "update",
                "drr_code" to u.drrCode, "block" to blk, "date" to u.date, "status" to u.status,
                "progress" to u.progress.toString(), "skilled" to u.skilled.toString(),
                "unskilled" to u.unskilled.toString())))
            dao.markUpdateSynced(u.drrCode, u.date); ok++
        } catch (e: java.io.IOException) {
            offline = true; fail++; if (firstError.isEmpty()) firstError = "No internet (${e.message})"
        } catch (e: Exception) {
            fail++; if (firstError.isEmpty()) firstError = "Update ${u.drrCode} ${u.date}: ${e.message}"
        }
    }
    "Sent: $ok   Failed: $fail" + if (firstError.isNotEmpty()) "\nFirst error: $firstError" else ""
}

@Composable
fun SyncScreen(block: String, dao: AppDao, onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val pendingP by dao.pendingProjects().collectAsState(0)
    val pendingU by dao.pendingUpdates().collectAsState(0)
    Column(
        Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Sync with Kobo", style = MaterialTheme.typography.titleLarge)
        Text(
            if (pendingP + pendingU == 0) "Everything on this phone is sent to Kobo."
            else "Not sent yet: $pendingP projects, $pendingU daily updates",
            fontWeight = FontWeight.Bold,
            color = if (pendingP + pendingU == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
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
        HorizontalDivider()
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("Log out") }
        Text("Data on this phone stays. Log in again with your email and password.", style = MaterialTheme.typography.bodySmall)
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
private fun fetchAll(query: String? = null): List<JSONObject> {
    val out = ArrayList<JSONObject>()
    val q = if (query != null) "&query=" + URLEncoder.encode(query, "UTF-8") else ""
    var url: String? = "${KoboConfig.KF_SERVER}/api/v2/assets/${KoboConfig.FORM_UID}/data/?format=json&limit=1000$q"
    while (url != null) {
        val j = JSONObject(get(url))
        val arr = j.getJSONArray("results")
        for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
        url = if (j.isNull("next")) null else j.optString("next").replace("http://", "https://").ifEmpty { null }
    }
    return out.sortedBy { it.optLong("_id") }
}

// ---------- Block accounts (email + password) ----------

// Latest account of every block, from Kobo
private fun fetchAccounts(): List<Account> =
    fetchAll("{\"record_type\":\"account\"}")
        .filter { it.optString("block").isNotEmpty() }
        .associateBy { it.optString("block") }          // oldest first, so the newest one wins
        .values
        .map { Account(it.optString("block"), it.optString("email").trim().lowercase(), it.optString("password"), true) }

// Admin: send new / changed accounts to Kobo
suspend fun sendAccounts(dao: AppDao): String = withContext(Dispatchers.IO) {
    var ok = 0
    var err = ""
    for (a in dao.unsyncedAccounts()) {
        try {
            post(xml(KoboConfig.FORM_UID, linkedMapOf(
                "record_type" to "account", "drr_code" to "ACCOUNT-${a.block}",
                "block" to a.block, "email" to a.email, "password" to a.password)))
            dao.markAccountSynced(a.block); ok++
        } catch (e: Exception) {
            if (err.isEmpty()) err = e.message ?: "error"
        }
    }
    if (err.isEmpty()) "Sent to Kobo: $ok account(s)" else "Sent: $ok. Error: $err"
}

// Block member login. Online check first (so a changed password works at once);
// offline it falls back to the account saved on this phone at the last login.
suspend fun login(dao: AppDao, emailRaw: String, password: String): Pair<String?, String> = withContext(Dispatchers.IO) {
    val email = emailRaw.trim().lowercase()
    try {
        val a = fetchAccounts().firstOrNull { it.email == email && it.password == password }
        if (a != null) {
            dao.saveAccount(a)
            return@withContext a.block to ""
        }
        return@withContext null to "Wrong email or password."
    } catch (e: java.io.IOException) {
        val a = dao.accountList().firstOrNull { it.email == email && it.password == password }
        if (a != null) return@withContext a.block to ""
        return@withContext null to "No internet or Kobo error (${e.message}). The first login needs internet."
    } catch (e: Exception) {
        return@withContext null to "Error: ${e.message}"
    }
}

// Download from Kobo into this phone.
// block = null -> everything (Admin).  block = "B" -> only that TM's block (restore after losing a phone).
suspend fun pullData(dao: AppDao, block: String?): String = withContext(Dispatchers.IO) {
    var np = 0
    var nu = 0
    var kept = 0
    var na = 0
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
        if (block == null) {   // Admin also gets the block accounts
            val local = dao.accountList().associateBy { it.block }
            for (a in fetchAccounts()) {
                if (local[a.block]?.synced == false) { kept++; continue }
                dao.saveAccount(a); na++
            }
        }
    } catch (e: Exception) {
        return@withContext "No internet or error: ${e.message}"
    }
    "Downloaded: $np projects, $nu daily updates" + (if (block == null) ", $na accounts" else "") + if (kept > 0) "  (kept $kept unsent items on this phone)" else ""
}
