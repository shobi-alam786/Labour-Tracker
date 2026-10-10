package com.shobi.labourtracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

// ---------------------------------------------------------------------------
// Small settings store. Same SharedPreferences file ("app") that MainActivity already uses
// for "block" and "admin".
// ---------------------------------------------------------------------------
object ReminderPrefs {
    const val FILE = "app"
    const val KEY_BACKFILL = "status_backfill_v1"
    private const val KEY_ENABLED = "reminder_enabled"
    private const val KEY_LAST_NOTIFIED = "reminder_last_notified"
    private const val KEY_NOTIF_ASKED = "notif_permission_asked"

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun prefs(ctx: Context) = p(ctx)
    fun enabled(ctx: Context): Boolean = p(ctx).getBoolean(KEY_ENABLED, true)
    fun setEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean(KEY_ENABLED, v).apply()

    // One notification per calendar day (Asia/Dhaka), even if the receiver runs twice.
    fun alreadyNotified(ctx: Context, day: LocalDate): Boolean =
        p(ctx).getString(KEY_LAST_NOTIFIED, null) == day.toString()

    fun markNotified(ctx: Context, day: LocalDate) =
        p(ctx).edit().putString(KEY_LAST_NOTIFIED, day.toString()).apply()

    fun notificationAsked(ctx: Context): Boolean = p(ctx).getBoolean(KEY_NOTIF_ASKED, false)
    fun setNotificationAsked(ctx: Context) = p(ctx).edit().putBoolean(KEY_NOTIF_ASKED, true).apply()

    // The reminder belongs to a logged-in block member. Admin phones and logged-out phones get none.
    fun reminderBlock(ctx: Context): String? {
        val sp = p(ctx)
        if (sp.getBoolean("admin", false)) return null
        return sp.getString("block", null)
    }
}

// ---------------------------------------------------------------------------
// Scheduling
//
// One alarm at a time, always the NEXT working-day 09:30 (Asia/Dhaka). The receiver schedules the
// following one every time it runs. The PendingIntent is identical on every call, so scheduling
// again REPLACES the alarm instead of adding a second one.
//
// Android limits: from Android 12 exact alarms need the "Alarms & reminders" permission (not granted
// by default from Android 13). Without it we fall back to setAndAllowWhileIdle, which Android may
// deliver a few minutes late (more in Doze / battery saver).
// ---------------------------------------------------------------------------
object ReminderScheduler {
    const val ACTION_ALARM = "com.shobi.labourtracker.ACTION_DAILY_REMINDER"
    private const val REQUEST_CODE = 9301

    private fun alarmManager(ctx: Context) = ctx.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pendingIntent(ctx: Context): PendingIntent {
        val app = ctx.applicationContext
        val intent = Intent(app, ReminderReceiver::class.java).setAction(ACTION_ALARM)
        return PendingIntent.getBroadcast(app, REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun canScheduleExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager(ctx).canScheduleExactAlarms()

    // Schedules the first working-day 09:30 strictly after `after`.
    fun scheduleNext(ctx: Context, after: ZonedDateTime = AppTime.now()) {
        if (!ReminderPrefs.enabled(ctx)) { cancel(ctx); return }
        val millis = AppTime.nextReminder(after).toInstant().toEpochMilli()
        val am = alarmManager(ctx)
        val pi = pendingIntent(ctx)
        try {
            if (canScheduleExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } catch (e: SecurityException) {
            // Exact-alarm permission was withdrawn between the check and the call.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }

    fun cancel(ctx: Context) {
        alarmManager(ctx).cancel(pendingIntent(ctx))
    }
}

// ---------------------------------------------------------------------------
// Receiver: the alarm itself, plus everything that clears or invalidates alarms
// (reboot, app update, clock / time-zone change, exact-alarm permission change).
// ---------------------------------------------------------------------------
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val action = intent.action
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ReminderRunner.handle(app, action)
            } catch (e: Exception) {
                Log.e("ReminderReceiver", "Reminder run failed", e)
            } finally {
                result.finish()
            }
        }
    }
}

object ReminderRunner {
    private const val TAG = "ReminderRunner"

    // A late catch-up (reboot, app update ...) is no longer announced after this time of day.
    val LATE_CUTOFF: LocalTime = LocalTime.of(18, 0)

    suspend fun handle(ctx: Context, action: String?) {
        val now = AppTime.now()
        val isAlarm = action == ReminderScheduler.ACTION_ALARM

        // 1) Chain the next alarm FIRST, so nothing below can ever break the daily schedule.
        val todayDue = now.toLocalDate().atTime(AppTime.REMINDER_TIME).atZone(AppTime.ZONE)
        ReminderScheduler.scheduleNext(ctx, if (isAlarm && now.isBefore(todayDue)) todayDue else now)

        // 2) Only a logged-in block member with the reminder switched on is checked.
        if (!ReminderPrefs.enabled(ctx)) return
        val block = ReminderPrefs.reminderBlock(ctx) ?: return

        // 3) Friday, Saturday and times before 09:30 do nothing (no status change, no notification).
        if (!AppTime.reminderDue(now)) {
            Log.d(TAG, "Skipped: not due at $now")
            return
        }

        // 4) Compare against TODAY's saved updates (history never counts). Marks Pending / Ongoing.
        val dao = AppDb.get(ctx).dao()
        val missing = StatusEngine.runDailyCheck(dao, block, now)
        if (missing.isEmpty()) return

        // 5) Notify once per day.
        val today = now.toLocalDate()
        val timeOk = isAlarm || now.toLocalTime().isBefore(LATE_CUTOFF)
        if (timeOk && !ReminderPrefs.alreadyNotified(ctx, today)) {
            ReminderNotifier.showMissing(ctx, missing)
            ReminderPrefs.markNotified(ctx, today)
        }
    }
}

// ---------------------------------------------------------------------------
// Notification (with sound)
// ---------------------------------------------------------------------------
object ReminderNotifier {
    // The sound of a channel is fixed when it is created, hence a versioned id.
    const val CHANNEL_ID = "daily_update_reminder_v1"
    private const val NOTIFICATION_ID = 9302
    private const val MAX_LISTED = 8

    fun ensureChannel(ctx: Context) {
        val nm = ctx.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "Daily update reminder", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Reminds you at 9:30 AM when a project has no labour update for today."
            enableVibration(true)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION), attrs)
        }
        nm.createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, title: String, text: String, bigText: String) {
        val app = ctx.applicationContext
        ensureChannel(app)
        val nmc = NotificationManagerCompat.from(app)
        if (!nmc.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reminder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
        // Fixed id: a second post REPLACES the first one instead of stacking a duplicate.
        nmc.notify(NOTIFICATION_ID, n)
    }

    fun showMissing(ctx: Context, missing: List<Project>) {
        if (missing.isEmpty()) return
        val labels = missing.map { "${it.drrCode} (${it.subBlock})" }
        val shown = labels.take(MAX_LISTED)
        val more = labels.size - shown.size
        val list = shown.joinToString(", ") + if (more > 0) " and $more more" else ""
        val title = if (missing.size == 1) "1 project needs today's labour update"
        else "${missing.size} projects need today's labour update"
        post(ctx, title, list, "Missing today's update:\n$list\n\nOpen Labour Tracker and record skilled / unskilled workers.")
    }

    fun showTest(ctx: Context) {
        post(ctx, "Test reminder", "If you hear a sound, reminders work on this phone.",
            "This is a test of the daily 9:30 AM reminder. Real reminders list the DRR-Codes that need an update.")
    }
}

// ---------------------------------------------------------------------------
// What the person can check / fix in Android settings
// ---------------------------------------------------------------------------
object ReminderHealth {
    fun notificationsAllowed(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx.applicationContext).areNotificationsEnabled()

    fun needsNotificationPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    fun exactAlarmsAllowed(ctx: Context): Boolean = ReminderScheduler.canScheduleExact(ctx)

    fun batteryUnrestricted(ctx: Context): Boolean {
        val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }
}
