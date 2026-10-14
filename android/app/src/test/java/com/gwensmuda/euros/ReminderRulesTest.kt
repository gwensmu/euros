package com.gwensmuda.euros
import java.time.Instant
import java.time.ZoneId
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class ReminderRulesTest {
 private val now=Instant.parse("2026-10-14T10:00:00Z");private val zone=ZoneId.of("America/Chicago")
 private fun activity(at:String="2026-10-14T18:00:00-05:00",status:String="pending")=Record("activities","a",1,JSONObject().put("title","BJJ").put("scheduled_at",at).put("status",status).toString())
 @Test fun optInAndPermissionAreNotInventedTimes() { assertNull(ReminderRules.due(activity(),ReminderSettings(),now,zone)) }
 @Test fun completionSkipDeletionCancel() { for(status in listOf("done","skipped")) assertNull(ReminderRules.due(activity(status=status),ReminderSettings(true),now,zone));assertNull(ReminderRules.due(activity().copy(deleted=true),ReminderSettings(true),now,zone)) }
 @Test fun overdueDoesNotBurst() { assertNull(ReminderRules.due(activity("2026-10-13T18:00:00-05:00"),ReminderSettings(true),now,zone)) }
 @Test fun movingKeepsIdentityWithNewTime() { val before=ReminderRules.due(activity(),ReminderSettings(true),now,zone)!!;val after=ReminderRules.due(activity("2026-10-15T18:00:00-05:00"),ReminderSettings(true),now,zone)!!;assertEquals(before.activityId,after.activityId);assertEquals(86400L,after.due.epochSecond-before.due.epochSecond) }
 @Test fun overnightQuietHoursShiftToNextMorning() { val result=ReminderRules.afterQuiet(Instant.parse("2026-10-15T04:00:00Z"),ReminderSettings(true,"22:00","07:00"),zone);assertEquals(Instant.parse("2026-10-15T12:00:00Z"),result) }
 @Test fun sameDayQuietHoursAndBoundaries() { val settings=ReminderSettings(true,"12:00","14:00");val start=Instant.parse("2026-10-14T17:00:00Z");assertEquals(Instant.parse("2026-10-14T19:00:00Z"),ReminderRules.afterQuiet(start,settings,zone));val end=Instant.parse("2026-10-14T19:00:00Z");assertEquals(end,ReminderRules.afterQuiet(end,settings,zone)) }
 @Test fun fallBackUsesLocalQuietEnd() { assertEquals(Instant.parse("2026-11-01T13:00:00Z"),ReminderRules.afterQuiet(Instant.parse("2026-11-01T04:00:00Z"),ReminderSettings(true,"22:00","07:00"),zone)) }
 @Test fun timezoneChangeRecomputesQuietWindow() { val at=Instant.parse("2026-10-15T04:00:00Z");val settings=ReminderSettings(true,"22:00","07:00");assertNotEquals(ReminderRules.afterQuiet(at,settings,zone),ReminderRules.afterQuiet(at,settings,ZoneId.of("Europe/Paris"))) }
 @Test fun snoozeDoesNotMoveActivity() { val result=ReminderRules.due(activity(),ReminderSettings(true),now,zone,now.plusSeconds(1800))!!;assertEquals(now.plusSeconds(1800),result.due);assertEquals("2026-10-14T18:00:00-05:00",result.scheduledAt) }
 @Test fun terminalRecoveryChildrenSuppressPendingParent() {
  val parent=activity();fun child(id:String,status:String)=activity(status=status).copy(id=id,payload=JSONObject(activity(status=status).payload).put("parent_id",parent.id).toString())
  val done=child("done","done");val skipped=child("skipped","skipped")
  assertFalse(ReminderRules.isPending(parent,listOf(parent,done,skipped)))
  assertTrue(ReminderRules.isPending(parent,listOf(parent,done,child("pending","pending"))))
  assertTrue(ReminderRules.isPending(parent,listOf(parent,done.copy(deleted=true))))
 }
 @Test fun sameStartEndQuietHoursRejected() { assertTrue(runCatching{ReminderRules.afterQuiet(now,ReminderSettings(true,"22:00","22:00"),zone)}.isFailure) }
}
