package com.shobi.labourtracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test

class StatusAndReminderTest {
    private val dhaka = ZoneId.of("Asia/Dhaka")
    // 2026-10-11 is a Sunday, 2026-10-09 Friday, 2026-10-10 Saturday
    private val sunday = LocalDate.of(2026, 10, 11)

    private fun project(code: String, status: String, start: String = "2026-01-01") =
        Project(code, "Act", "A", "A06", start, status = status)

    private fun update(code: String, date: LocalDate, status: String = "Ongoing") =
        DailyUpdate(code, date.toString(), status, 50, 1, 1)

    @Test fun fridayAndSaturdayAreNotWorkingDays() {
        assertFalse(AppTime.isWorkingDay(LocalDate.of(2026, 10, 9)))
        assertFalse(AppTime.isWorkingDay(LocalDate.of(2026, 10, 10)))
        assertTrue(AppTime.isWorkingDay(sunday))
    }

    @Test fun nextReminderSkipsFridayAndSaturday() {
        val thursdayNoon = ZonedDateTime.of(2026, 10, 8, 12, 0, 0, 0, dhaka)
        val next = AppTime.nextReminder(thursdayNoon)
        assertEquals(sunday, next.toLocalDate())
        assertEquals(9, next.hour); assertEquals(30, next.minute)
    }

    @Test fun nextReminderNeverReturnsFridayOrSaturday() {
        var t = ZonedDateTime.of(2026, 10, 1, 0, 0, 0, 0, dhaka)
        repeat(60) {
            val n = AppTime.nextReminder(t)
            assertTrue(n.dayOfWeek != DayOfWeek.FRIDAY && n.dayOfWeek != DayOfWeek.SATURDAY)
            assertTrue(n.isAfter(t))
            t = n
        }
    }

    @Test fun beforeAndAtNineThirtySameDayThenNextDay() {
        assertEquals(sunday, AppTime.nextReminder(ZonedDateTime.of(2026, 10, 11, 9, 0, 0, 0, dhaka)).toLocalDate())
        assertEquals(sunday.plusDays(1), AppTime.nextReminder(ZonedDateTime.of(2026, 10, 11, 9, 30, 0, 0, dhaka)).toLocalDate())
    }

    @Test fun usesBangladeshTimeNotTheInputZone() {
        // Saturday 20:00 UTC is already Sunday 02:00 in Dhaka -> next is Sunday 09:30 Dhaka = 03:30 UTC
        val utc = ZonedDateTime.of(2026, 10, 10, 20, 0, 0, 0, ZoneId.of("UTC"))
        val next = AppTime.nextReminder(utc)
        assertEquals(sunday, next.toLocalDate())
        assertEquals(3, next.withZoneSameInstant(ZoneId.of("UTC")).hour)
    }

    @Test fun reminderDueOnlyOnWorkingDaysFromNineThirty() {
        assertFalse(AppTime.reminderDue(ZonedDateTime.of(2026, 10, 9, 10, 0, 0, 0, dhaka)))   // Friday
        assertFalse(AppTime.reminderDue(ZonedDateTime.of(2026, 10, 10, 10, 0, 0, 0, dhaka)))  // Saturday
        assertFalse(AppTime.reminderDue(ZonedDateTime.of(2026, 10, 11, 9, 29, 0, 0, dhaka)))
        assertTrue(AppTime.reminderDue(ZonedDateTime.of(2026, 10, 11, 9, 30, 0, 0, dhaka)))
    }

    @Test fun completedProjectIsNeverMarkedPending() {
        val ps = listOf(project("C", "Completed"), project("O", "Ongoing"))
        val plan = StatusRules.plan(ps, emptySet(), sunday, allowPending = true)
        assertEquals(listOf("O"), plan.toPending.map { it.drrCode })
        assertTrue(StatusRules.missing(ps, emptySet(), sunday).none { it.drrCode == "C" })
    }

    @Test fun historicalUpdatesDoNotCountAsToday() {
        val ups = listOf(update("O", sunday.minusDays(1)), update("P", sunday))
        val codes = StatusRules.codesUpdatedOn(ups, sunday)
        assertEquals(setOf("P"), codes)
        val plan = StatusRules.plan(listOf(project("O", "Ongoing"), project("P", "Pending")), codes, sunday, true)
        assertEquals(listOf("O"), plan.toPending.map { it.drrCode })
        assertEquals(listOf("P"), plan.toOngoing.map { it.drrCode })
    }

    @Test fun notYetStartedProjectIsNotChased() {
        val ps = listOf(project("F", "Pending", start = "2026-12-01"))
        assertTrue(StatusRules.missing(ps, emptySet(), sunday).isEmpty())
    }

    @Test fun noPendingBeforeDueTime() {
        val plan = StatusRules.plan(listOf(project("O", "Ongoing")), emptySet(), sunday, allowPending = false)
        assertTrue(plan.toPending.isEmpty())
    }

    @Test fun saveRules() {
        val today = sunday
        // today's update -> Ongoing
        assertEquals(ProjectStatus.Ongoing, StatusRules.statusAfterSave(ProjectStatus.Pending, update("X", today), "2026-10-05", today))
        // historical update changes nothing
        assertEquals(ProjectStatus.Pending, StatusRules.statusAfterSave(ProjectStatus.Pending, update("X", today.minusDays(3)), "2026-10-05", today))
        // completion step on the latest update -> Completed
        assertEquals(ProjectStatus.Completed, StatusRules.statusAfterSave(ProjectStatus.Ongoing, update("X", today, "Completed"), "2026-10-05", today))
        // completion step on an OLDER day than the latest update -> unchanged
        assertEquals(ProjectStatus.Ongoing, StatusRules.statusAfterSave(ProjectStatus.Ongoing, update("X", LocalDate.of(2026, 10, 1), "Completed"), "2026-10-05", today))
        // Completed never changes automatically
        assertEquals(ProjectStatus.Completed, StatusRules.statusAfterSave(ProjectStatus.Completed, update("X", today), "2026-10-05", today))
    }

    @Test fun oldRowsParseAsOngoing() {
        assertEquals(ProjectStatus.Ongoing, ProjectStatus.parse("Ongoing"))
        assertEquals(ProjectStatus.Ongoing, ProjectStatus.parse("whatever"))
        assertEquals(ProjectStatus.Completed, ProjectStatus.parse("completed"))
    }

    @Test fun searchByDrrCodeAndFilter() {
        val ps = listOf(project("DRR-100", "Pending"), project("DRR-200", "Completed"))
        assertEquals(listOf("DRR-200"), filterProjects(ps, StatusFilter.All, "200").map { it.drrCode })
        assertEquals(listOf("DRR-100"), filterProjects(ps, StatusFilter.Pending, "").map { it.drrCode })
        assertTrue(filterProjects(ps, StatusFilter.Ongoing, "").isEmpty())
    }
}
