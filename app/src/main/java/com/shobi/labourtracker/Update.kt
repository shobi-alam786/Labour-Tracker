package com.shobi.labourtracker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// In-app update for installs outside Google Play: reads the newest GitHub Release of the public repo,
// downloads its APK and opens Android's installer. The person still confirms the install.
data class UpdateInfo(val version: String, val notes: String, val apkUrl: String)

object Updater {
    const val REPO = "shobi-alam786/Labour-Tracker"

    private fun parts(v: String): List<Int> =
        v.trim().removePrefix("v").removePrefix("V").split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    // "1.10" is newer than "1.9"; missing parts count as 0 ("1.2" == "1.2.0").
    fun isNewer(remote: String, current: String): Boolean {
        val a = parts(remote)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    // (update or null, message for the screen)
    suspend fun check(): Pair<UpdateInfo?, String> = withContext(Dispatchers.IO) {
        try {
            val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "LabourTracker")
            c.connectTimeout = 15000
            c.readTimeout = 20000
            val code = c.responseCode
            if (code == 404) { c.disconnect(); return@withContext null to "No release is published yet." }
            if (code != 200) { c.disconnect(); return@withContext null to "GitHub answered HTTP $code." }
            val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            c.disconnect()
            val tag = json.optString("tag_name")
            var url = ""
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) { url = a.optString("browser_download_url"); break }
                }
            }
            if (tag.isBlank() || url.isBlank()) return@withContext null to "The newest release has no APK yet."
            val v = tag.removePrefix("v").removePrefix("V")
            if (!isNewer(tag, BuildConfig.VERSION_NAME)) return@withContext null to "You have the latest version (${BuildConfig.VERSION_NAME})."
            UpdateInfo(v, json.optString("body").trim().take(400), url) to "Version $v is available."
        } catch (e: IOException) {
            null to "No internet (${e.message})"
        } catch (e: Exception) {
            null to "Error: ${e.message}"
        }
    }

    suspend fun download(ctx: Context, url: String): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "LabourTracker-update.apk")
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "LabourTracker")
        c.connectTimeout = 15000
        c.readTimeout = 60000
        val code = c.responseCode
        if (code != 200) { c.disconnect(); throw IOException("Download failed (HTTP $code)") }
        c.inputStream.use { input -> out.outputStream().use { output -> input.copyTo(output) } }
        c.disconnect()
        out
    }

    // Returns a message when the person has to do something first, null when the installer was opened.
    fun install(ctx: Context, apk: File): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return "Allow \"Install unknown apps\" for Labour Tracker, come back and tap Install again."
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return null
    }
}

@Composable
fun UpdateSection(initial: UpdateInfo?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf(initial) }
    var apk by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var good by remember { mutableStateOf(true) }
    val available = info

    AppCard(Modifier.fillMaxWidth()) {
        Text("App update", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            "Installed version ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (available != null) {
            Spacer(Modifier.height(6.dp))
            Text("Version ${available.version} is available", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (available.notes.isNotEmpty()) Text(available.notes, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(10.dp))
        if (busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                Text("Working...", style = MaterialTheme.typography.bodyMedium)
            }
        } else if (available == null) {
            OutlinedButton(
                onClick = {
                    busy = true
                    scope.launch {
                        val (found, text) = Updater.check()
                        info = found; good = found != null || text.startsWith("You have"); msg = text; busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
            ) { Text("Check for update") }
        } else {
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val f = apk ?: Updater.download(ctx, available.apkUrl)
                            apk = f
                            val ask = Updater.install(ctx, f)
                            good = ask == null; msg = ask ?: "Installer opened. Tap Install there."
                        } catch (e: Exception) {
                            good = false; msg = e.message ?: "Download failed."
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)
            ) { Text(if (apk != null) "Install" else "Download and install") }
        }
        if (msg.isNotEmpty() && !busy) {
            Spacer(Modifier.height(10.dp))
            MessageBanner(if (good) BannerKind.Success else BannerKind.Error, msg)
        }
    }
}
