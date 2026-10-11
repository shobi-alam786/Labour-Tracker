package com.shobi.labourtracker

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import org.json.JSONObject

// Kobo settings. Nothing secret is stored in the source code.
// Values are entered once in the app (Setup screen) and kept in this phone's private storage.
// Build-time values from local.properties (BuildConfig) are only a fallback.
object KoboConfig {
    private const val DEFAULT_KF = "https://kf.kobotoolbox.org"

    private var prefs: android.content.SharedPreferences? = null
    fun init(ctx: android.content.Context) {
        prefs = ctx.applicationContext.getSharedPreferences("kobo_config", android.content.Context.MODE_PRIVATE)
    }
    private fun stored(k: String): String = prefs?.getString(k, null)?.trim().orEmpty()

    val KF_SERVER: String get() = stored("kf_server").ifEmpty { DEFAULT_KF }.trimEnd('/')
    // Submissions go to the "kc." host that belongs to the same server.
    val SERVER: String get() = KF_SERVER.replace("//kf.", "//kc.")

    // Three separate Kobo forms (asset UIDs from the Kobo project URLs)
    // Form UIDs are identifiers, not secrets, so they are built in as defaults (can still be changed in Kobo setup).
    private const val DEFAULT_PROJECT_UID = "aHmuYpVMBT3kBeTCkgodk4"   // Register Project
    private const val DEFAULT_UPDATE_UID = "a6NyLaFx7XLYypjjW7JXyN"    // Daily Updates (also carries the project details)
    private const val DEFAULT_ACCOUNT_UID = "avG2ib884Dha9rCTtqcihF"   // Account Access
    val PROJECT_UID: String get() = stored("project_uid").ifEmpty { DEFAULT_PROJECT_UID }
    val UPDATE_UID: String get() = stored("update_uid").ifEmpty { DEFAULT_UPDATE_UID }
    val ACCOUNT_UID: String get() = stored("account_uid").ifEmpty { DEFAULT_ACCOUNT_UID }

    // One time after upgrading from the single combined form: everything on the phone is sent once to the three new forms.
    fun consumeFormsMigration(): Boolean {
        val sp = prefs ?: return false
        if (sp.getBoolean("forms_split_v2", false)) return false
        sp.edit().putBoolean("forms_split_v2", true).apply()
        return true
    }

    val TOKEN: String get() = stored("token").ifEmpty { BuildConfig.KOBO_TOKEN }
    // Empty = admin login is switched off.
    val ADMIN_PIN: String get() = stored("admin_pin").ifEmpty { BuildConfig.ADMIN_PIN }
    val configured: Boolean
        get() = TOKEN.isNotBlank() && PROJECT_UID.isNotBlank() && UPDATE_UID.isNotBlank() && ACCOUNT_UID.isNotBlank()
    const val MISSING_TOKEN = "Kobo is not set up on this phone yet. Open Kobo setup and enter the server, the three form UIDs and the API token."

    // Returns true when a form UID changed: the caller then marks local data as "not sent yet",
    // so it is sent once to the new (empty) forms.
    fun save(kfServer: String, projectUid: String, updateUid: String, accountUid: String, token: String, adminPin: String): Boolean {
        val changed = projectUid.trim() != stored("project_uid") || updateUid.trim() != stored("update_uid") ||
            accountUid.trim() != stored("account_uid")
        prefs?.edit()
            ?.putString("kf_server", kfServer.trim())
            ?.putString("project_uid", projectUid.trim())
            ?.putString("update_uid", updateUid.trim())
            ?.putString("account_uid", accountUid.trim())
            ?.putString("token", token.trim())
            ?.putString("admin_pin", adminPin.trim())
            ?.apply()
        return changed
    }
    fun clear() { prefs?.edit()?.clear()?.apply() }
}

// Checks server + form UID + token without changing any data.
suspend fun testKoboConnection(kf: String, uid: String, token: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
    try {
        val c = URL("${kf.trim().trimEnd('/')}/api/v2/assets/${uid.trim()}/?format=json").openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Token ${token.trim()}")
        c.connectTimeout = 15000
        c.readTimeout = 20000
        val code = c.responseCode
        c.disconnect()
        when (code) {
            200 -> true to "Connected. The form was found."
            401, 403 -> false to "The token was refused (HTTP $code). Check the API token."
            404 -> false to "Form not found (HTTP 404). Check the server and the Project / Asset UID."
            else -> false to "Kobo answered HTTP $code."
        }
    } catch (e: java.io.IOException) {
        false to "No internet or wrong server (${e.message})"
    } catch (e: Exception) {
        false to "Error: ${e.message}"
    }
}

private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun xml(formId: String, f: Map<String, String>): String {
    val body = f.entries.joinToString("") { "<${it.key}>${esc(it.value)}</${it.key}>" }
    return "<$formId id=\"$formId\">$body<meta><instanceID>uuid:${UUID.randomUUID()}</instanceID></meta></$formId>"
}

private fun post(xml: String): Boolean {
    if (!KoboConfig.configured) throw IllegalStateException(KoboConfig.MISSING_TOKEN)
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

// Only one send at a time: two overlapping syncs could post the same unsent record twice.
private val syncMutex = Mutex()

suspend fun syncAll(dao: AppDao, block: String): String = withContext(Dispatchers.IO) { syncMutex.withLock {
    var ok = 0
    var fail = 0
    var firstError = ""
    var offline = false
    for (p in dao.unsyncedProjects()) {
        if (offline) break
        try {
            post(xml(KoboConfig.PROJECT_UID, linkedMapOf(
                "drr_code" to p.drrCode, "block" to p.block, "activity" to p.activity,
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
            // Every daily update also carries the project details, so the Daily Updates table in Kobo
            // can be read on its own (activity, sub-block, start / end date next to the labour numbers).
            val proj = dao.getProject(u.drrCode)
            post(xml(KoboConfig.UPDATE_UID, linkedMapOf(
                "drr_code" to u.drrCode, "block" to (proj?.block ?: block),
                "activity" to (proj?.activity ?: ""), "sub_block" to (proj?.subBlock ?: ""),
                "start_date" to (proj?.startDate ?: ""), "end_date" to (proj?.endDate ?: ""),
                "date" to u.date, "status" to u.status,
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
} }

// Short text for the snackbar after a save. `result` is the text returned by syncAll.
fun friendlySyncMessage(result: String): String = when {
    result.contains("No internet", ignoreCase = true) -> "Saved on this phone. It will be sent when internet is available."
    Regex("Failed: [1-9]").containsMatchIn(result) -> "Saved on this phone. Some items were not sent - see Kobo sync."
    result.contains("Sent: 0") -> "Saved."
    else -> "Saved and sent to Kobo."
}

private fun syncFailed(msg: String): Boolean =
    msg.contains("No internet", ignoreCase = true) || msg.contains("error", ignoreCase = true) ||
        Regex("Failed: [1-9]").containsMatchIn(msg)

@Composable
fun SyncScreen(block: String, dao: AppDao, onLogout: () -> Unit, onSetup: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmResend by remember { mutableStateOf(false) }
    val pendingP by dao.pendingProjects().collectAsState(0)
    val pendingU by dao.pendingUpdates().collectAsState(0)
    val pending = pendingP + pendingU
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column {
            Text("Kobo sync", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "Records are always saved on this phone first. Send them when you have internet.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!KoboConfig.configured) {
            MessageBanner(BannerKind.Error, KoboConfig.MISSING_TOKEN)
            if (onSetup != null) Button(onClick = onSetup, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("Open Kobo setup") }
        }
        if (pending == 0) MessageBanner(BannerKind.Success, "Everything on this phone is sent to Kobo.")
        else MessageBanner(BannerKind.Info, "Not sent yet: $pendingP projects, $pendingU daily updates")

        if (busy) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                Text("Working...", style = MaterialTheme.typography.bodyMedium)
            }
        } else if (msg.isNotEmpty()) {
            MessageBanner(if (syncFailed(msg)) BannerKind.Error else BannerKind.Success, msg)
        }

        AppCard(Modifier.fillMaxWidth()) {
            Text("Send", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text("Sends your new projects and daily updates to Kobo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { busy = true; scope.launch { msg = syncAll(dao, block); busy = false } },
                enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
            ) { Text(if (busy) "Working..." else "Send now") }
        }

        AppCard(Modifier.fillMaxWidth()) {
            Text("Restore", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Lost or changed your phone? Restore all your projects and daily updates from Kobo. Data not yet sent from this phone is never overwritten.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { busy = true; scope.launch { msg = pullData(dao, block); busy = false } },
                enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
            ) { Text(if (busy) "Working..." else "Restore my Block $block data") }
        }

        AppCard(Modifier.fillMaxWidth()) {
            Text("Re-send everything", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "Normally not needed: changing the form UIDs in Kobo setup already does this. Records already in Kobo are sent again, so use it only when you must.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { confirmResend = true },
                enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
            ) { Text("Re-send all my data") }
        }

        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Text("Log out") }
        Text("Data on this phone stays. Log in again with your email and password.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (confirmResend) {
        AlertDialog(
            onDismissRequest = { confirmResend = false },
            title = { Text("Re-send all data?", fontWeight = FontWeight.Bold) },
            text = { Text("Every project and daily update on this phone will be sent to Kobo again. Records already in Kobo will appear twice there. Continue only if you must.") },
            confirmButton = {
                Button({
                    confirmResend = false
                    busy = true
                    scope.launch { dao.markAllUnsynced(); msg = syncAll(dao, block); busy = false }
                }) { Text("Re-send") }
            },
            dismissButton = { TextButton({ confirmResend = false }) { Text("Cancel") } }
        )
    }
}

private fun get(url: String): String {
    if (!KoboConfig.configured) throw IllegalStateException(KoboConfig.MISSING_TOKEN)
    val c = URL(url).openConnection() as HttpURLConnection
    c.setRequestProperty("Authorization", "Token ${KoboConfig.TOKEN}")
    c.connectTimeout = 15000
    c.readTimeout = 30000
    val t = c.inputStream.bufferedReader().use { it.readText() }
    c.disconnect()
    return t
}

// All submissions of one form, oldest first (so the newest one wins when saved in order)
private fun fetchAll(uid: String, query: String? = null): List<JSONObject> {
    val out = ArrayList<JSONObject>()
    val q = if (query != null) "&query=" + URLEncoder.encode(query, "UTF-8") else ""
    var url: String? = "${KoboConfig.KF_SERVER}/api/v2/assets/$uid/data/?format=json&limit=1000$q"
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
    fetchAll(KoboConfig.ACCOUNT_UID)
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
            post(xml(KoboConfig.ACCOUNT_UID, linkedMapOf(
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
        val projectRows = fetchAll(KoboConfig.PROJECT_UID)
        val updateRows = fetchAll(KoboConfig.UPDATE_UID)
        val codes = HashSet<String>()
        val restoredNew = ArrayList<String>()
        for (o in projectRows) {
            val code = o.optString("drr_code")
            val blk = o.optString("block")
            if (code.isEmpty() || (block != null && blk != block)) continue
            codes.add(code)
            val local = dao.getProject(code)
            if (local != null && !local.synced) { kept++; continue }
            dao.saveProject(Project(code, o.optString("activity"), blk, o.optString("sub_block"),
                o.optString("start_date"), o.optString("end_date"),
                status = local?.status ?: ProjectStatus.Pending.label, progress = local?.progress ?: 0, synced = true))
            if (local == null) restoredNew.add(code)
            np++
        }
        for (o in updateRows) {
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
        // Status is local-only. A project that is new on this phone gets Completed if its newest update was a
        // completion step, otherwise Ongoing / Pending depending on today's update. Existing local status is kept.
        for (code in restoredNew) dao.setStatus(code, StatusEngine.statusForRestored(dao, code).label)
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
