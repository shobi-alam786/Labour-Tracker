@file:OptIn(ExperimentalMaterial3Api::class)

package com.shobi.labourtracker

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun Context.findComponentActivity(): ComponentActivity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is ComponentActivity) return c
        c = c.baseContext
    }
    return null
}

private fun Context.openSettings(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        try {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e2: ActivityNotFoundException) {
            // nothing else we can open
        }
    }
}

@Composable
fun ReminderScreen() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var enabled by remember { mutableStateOf(ReminderPrefs.enabled(ctx)) }

    // Re-check the three Android settings every time the person comes back from Android Settings.
    DisposableEffect(ctx) {
        val activity = ctx.findComponentActivity()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    val notifOk = remember(tick) { ReminderHealth.notificationsAllowed(ctx) }
    val exactOk = remember(tick) { ReminderHealth.exactAlarmsAllowed(ctx) }
    val batteryOk = remember(tick) { ReminderHealth.batteryUnrestricted(ctx) }
    val next = remember(tick, enabled) { AppTime.nextReminder(AppTime.now()) }
    val nextText = remember(next) { next.format(DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy 'at' h:mm a", Locale.ENGLISH)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Daily update reminder", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "9:30 AM, Sunday to Thursday (Bangladesh time). No reminder on Friday and Saturday.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    ReminderPrefs.setEnabled(ctx, it)
                    if (it) ReminderScheduler.scheduleNext(ctx) else ReminderScheduler.cancel(ctx)
                })
            }
            if (enabled) {
                Spacer(Modifier.height(10.dp))
                Text("Next reminder: $nextText", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }

        Text(
            "If a project has no update for today at 9:30 AM it is marked Pending and you get a notification with its DRR-Code. " +
                "Completed projects are never changed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        SettingRow(
            title = "Notifications",
            ok = notifOk,
            okText = "Allowed",
            badText = "Blocked - you will not see the reminder",
            actionLabel = "Allow"
        ) {
            val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ReminderHealth.needsNotificationPermission(ctx) && !ReminderPrefs.notificationAsked(ctx)
            if (canAsk) {
                ReminderPrefs.setNotificationAsked(ctx)
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                ctx.openSettings(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                )
            }
        }

        SettingRow(
            title = "Exact time (Alarms & reminders)",
            ok = exactOk,
            okText = "Allowed - reminder arrives at 9:30",
            badText = "Not allowed - Android may deliver it a few minutes late",
            actionLabel = "Allow"
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
            }
        }

        SettingRow(
            title = "Battery",
            ok = batteryOk,
            okText = "Unrestricted",
            badText = "Optimised - Android can delay or skip reminders to save battery",
            actionLabel = "Open"
        ) {
            ctx.openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        OutlinedButton(
            onClick = { ReminderNotifier.showTest(ctx) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) { Text("Send a test reminder (checks sound)") }

        MessageBanner(
            BannerKind.Info,
            "Some phones (Xiaomi, Oppo, Vivo, Realme, Infinix, Tecno, Huawei) also have an \"Autostart\" or " +
                "\"Background activity\" switch in their own settings. Turn it on for Labour Tracker, otherwise the reminder " +
                "may not appear after the phone restarts or the app is swiped away."
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    ok: Boolean,
    okText: String,
    badText: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    if (ok) okText else badText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
            if (!ok) {
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onAction, shape = RoundedCornerShape(12.dp)) { Text(actionLabel) }
            }
        }
    }
}
