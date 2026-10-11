package com.shobi.labourtracker

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// ---------------------------------------------------------------------------
// Project status
//
// Stored in the existing `projects.status` text column (no schema change).
//   Pending   - active project that has NOT received today's labour update
//   Ongoing   - active project whose update for today is recorded
//   Completed - explicitly completed by the user (manual button, or the existing
//               "Project completed" / 100% step of the Daily Update screen)
// Status is local only: Kobo has no project-status field, so it is never uploaded.
// ---------------------------------------------------------------------------
enum class ProjectStatus(val label: String) {
    Pending("Pending"),
    Ongoing("Ongoing"),
    Completed("Completed");

    companion object {
        // Old rows only ever contain "Ongoing". Unknown text is treated as Ongoing.
        fun parse(raw: String?): ProjectStatus =
            values().firstOrNull { it.label.equals(raw?.trim(), ignoreCase = true) } ?: Ongoing
    }
}

fun Project.projectStatus(): ProjectStatus = ProjectStatus.parse(status)

// ---------------------------------------------------------------------------
// Time rules (Bangladesh)
// ---------------------------------------------------------------------------
object AppTime {
    val ZONE: ZoneId = ZoneId.of("Asia/Dhaka")
    val REMINDER_TIME: LocalTime = LocalTime.of(9, 30)

    fun now(): ZonedDateTime = ZonedDateTime.now(ZONE)
    fun today(): LocalDate = LocalDate.now(ZONE)

    // Friday and Saturday are non-working days.
    fun isWorkingDay(d: LocalDate): Boolean =
        d.dayOfWeek != DayOfWeek.FRIDAY && d.dayOfWeek != DayOfWeek.SATURDAY

    // The 9:30 check may run (and may mark projects Pending) only on a working day, from 09:30 on.
    fun reminderDue(now: ZonedDateTime): Boolean {
        val local = now.withZoneSameInstant(ZONE)
        return isWorkingDay(local.toLocalDate()) && !local.toLocalTime().isBefore(REMINDER_TIME)
    }

    // First working-day 09:30 (Asia/Dhaka) that is strictly after `after`.
    fun nextReminder(after: ZonedDateTime): ZonedDateTime {
        var day = after.withZoneSameInstant(ZONE).toLocalDate()
        while (true) {
            if (isWorkingDay(day)) {
                val t = day.atTime(REMINDER_TIME).atZone(ZONE)
                if (t.isAfter(after)) return t
            }
            day = day.plusDays(1)
        }
    }
}

// ---------------------------------------------------------------------------
// Pure rules (no database, no Android) - covered by unit tests
// ---------------------------------------------------------------------------
object StatusRules {

    // Active = not Completed, and already started. A Completed project is never touched.
    fun isActive(p: Project, today: LocalDate): Boolean {
        if (p.projectStatus() == ProjectStatus.Completed) return false
        val start = runCatching { LocalDate.parse(p.startDate) }.getOrNull()
        return start == null || !start.isAfter(today)
    }

    // Projects that have an update dated exactly `today`. Yesterday or older never counts.
    fun codesUpdatedOn(updates: List<DailyUpdate>, today: LocalDate): Set<String> {
        val d = today.toString()
        return updates.filter { it.date == d }.map { it.drrCode }.toSet()
    }

    // Active projects that still lack today's update (these are listed in the notification).
    fun missing(projects: List<Project>, updatedToday: Set<String>, today: LocalDate): List<Project> =
        projects.filter { isActive(it, today) && it.drrCode !in updatedToday }
            .sortedWith(compareBy({ it.subBlock }, { it.drrCode }))

    class Plan(val toPending: List<Project>, val toOngoing: List<Project>)

    // What has to change in the database. Completed / not-yet-started projects are skipped entirely.
    fun plan(projects: List<Project>, updatedToday: Set<String>, today: LocalDate, allowPending: Boolean): Plan {
        val pending = ArrayList<Project>()
        val ongoing = ArrayList<Project>()
        for (p in projects) {
            if (!isActive(p, today)) continue
            val st = p.projectStatus()
            if (p.drrCode in updatedToday) {
                if (st != ProjectStatus.Ongoing) ongoing.add(p)
            } else if (allowPending && st != ProjectStatus.Pending) {
                pending.add(p)
            }
        }
        return Plan(pending, ongoing)
    }

    // Status after the user saves a daily update.
    //  - Completed stays Completed (never changed automatically).
    //  - A completion step (update.status == "Completed") on the LATEST update completes the project.
    //  - An update dated today makes it Ongoing.
    //  - A back-dated / historical update changes nothing.
    // latestDateBefore = newest update date of this project BEFORE the save (null = none).
    fun statusAfterSave(
        current: ProjectStatus,
        update: DailyUpdate,
        latestDateBefore: String?,
        today: LocalDate
    ): ProjectStatus {
        if (current == ProjectStatus.Completed) return current
        val isLatest = latestDateBefore == null || update.date >= latestDateBefore
        if (update.status.equals(ProjectStatus.Completed.label, ignoreCase = true) && isLatest) {
            return ProjectStatus.Completed
        }
        if (update.date == today.toString()) return ProjectStatus.Ongoing
        return current
    }

    // Admin phones only download data and cannot mark anything, so the status is derived
    // from the newest update (the same information the old app showed).
    fun derivedForAdmin(projects: List<Project>, updates: List<DailyUpdate>): List<Project> {
        val latest = updates.groupBy { it.drrCode }.mapValues { e -> e.value.maxByOrNull { it.date } }
        return projects.map { p ->
            val done = latest[p.drrCode]?.status.equals(ProjectStatus.Completed.label, ignoreCase = true)
            p.copy(status = if (done) ProjectStatus.Completed.label else ProjectStatus.Ongoing.label)
        }
    }
}

// ---------------------------------------------------------------------------
// Database-facing engine
// ---------------------------------------------------------------------------
object StatusEngine {

    // Called after a daily update was saved. `latestBefore` must be read BEFORE saving.
    suspend fun applyAfterSave(dao: AppDao, update: DailyUpdate, latestBefore: String?) {
        val p = dao.getProject(update.drrCode) ?: return
        val cur = p.projectStatus()
        val next = StatusRules.statusAfterSave(cur, update, latestBefore, AppTime.today())
        if (next != cur || p.status != next.label) dao.setStatus(p.drrCode, next.label)
    }

    // Saves a project (e.g. new end date) without overwriting a status that changed meanwhile.
    suspend fun saveProjectKeepingStatus(dao: AppDao, p: Project) {
        val cur = dao.getProject(p.drrCode)
        dao.saveProject(if (cur != null) p.copy(status = cur.status) else p)
    }

    // Manual completion. If there is no end date yet, today's date is used (same as the Daily Update
    // completion step); that goes through the normal project sync, which already carries end_date.
    suspend fun markCompleted(dao: AppDao, code: String) {
        val p = dao.getProject(code) ?: return
        if (p.endDate.isBlank()) {
            dao.saveProject(p.copy(endDate = AppTime.today().toString(), status = ProjectStatus.Completed.label, synced = false))
        } else {
            dao.setStatus(code, ProjectStatus.Completed.label)
        }
    }

    // Manual reopen (the only way out of Completed).
    suspend fun reopen(dao: AppDao, code: String) {
        val today = AppTime.today().toString()
        val hasToday = dao.getUpdate(code, today) != null
        dao.setStatus(code, (if (hasToday) ProjectStatus.Ongoing else ProjectStatus.Pending).label)
    }

    // The 9:30 check, also used as a silent catch-up when the app opens.
    // Marks active projects Pending / Ongoing and returns every active project that still lacks today's update.
    suspend fun runDailyCheck(dao: AppDao, block: String, now: ZonedDateTime): List<Project> {
        val today = now.withZoneSameInstant(AppTime.ZONE).toLocalDate()
        val projects = dao.projectsOfBlock(block)
        val updatedToday = dao.updatesOn(today.toString()).map { it.drrCode }.toSet()
        val plan = StatusRules.plan(projects, updatedToday, today, allowPending = AppTime.reminderDue(now))
        plan.toPending.forEach { dao.setStatus(it.drrCode, ProjectStatus.Pending.label) }
        plan.toOngoing.forEach { dao.setStatus(it.drrCode, ProjectStatus.Ongoing.label) }
        return StatusRules.missing(projects, updatedToday, today)
    }

    // One-time upgrade step. Before this version "Completed" was only derived from the newest update.
    // Projects whose NEWEST update was saved through the completion step keep showing Completed;
    // everything else is left alone. Never run again afterwards (so "Reopen" sticks).
    suspend fun backfillCompletedOnce(dao: AppDao, prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean(ReminderPrefs.KEY_BACKFILL, false)) return
        for (p in dao.projectList()) {
            if (p.projectStatus() == ProjectStatus.Completed) continue
            val latest = dao.latestUpdate(p.drrCode) ?: continue
            if (latest.status.equals(ProjectStatus.Completed.label, ignoreCase = true)) {
                dao.setStatus(p.drrCode, ProjectStatus.Completed.label)
            }
        }
        prefs.edit().putBoolean(ReminderPrefs.KEY_BACKFILL, true).apply()
    }

    // Status for a project that is being restored from Kobo onto this phone for the first time.
    suspend fun statusForRestored(dao: AppDao, code: String): ProjectStatus {
        val latest = dao.latestUpdate(code)
        if (latest != null && latest.status.equals(ProjectStatus.Completed.label, ignoreCase = true)) {
            return ProjectStatus.Completed
        }
        // End date is only set when a project is completed (the end-date field was removed from Daily Update).
        val end = runCatching { LocalDate.parse(dao.getProject(code)?.endDate) }.getOrNull()
        if (end != null && !end.isAfter(AppTime.today())) return ProjectStatus.Completed
        return if (dao.getUpdate(code, AppTime.today().toString()) != null) ProjectStatus.Ongoing else ProjectStatus.Pending
    }
}
