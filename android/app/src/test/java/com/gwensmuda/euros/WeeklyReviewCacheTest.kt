package com.gwensmuda.euros

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class WeeklyReviewCacheTest {
 private val week=LocalDate.parse("2026-10-12")
 private val today=week.plusDays(2)
 private fun response()=JSONObject("""{"summary":"Two training sessions logged.","adjustments":["Keep the planned recovery."],"coverage":{"through_date":"2026-10-14","logged_activity_count":2,"unlogged_activity_count":1,"food_days":2,"covered_days":3},"model":"test-model","provider":"test"}""")
 private val record=Record("activities","a",1,"""{"status":"done","scheduled_at":"2026-10-14T10:00:00-05:00"}""")
 @Test fun reorderedRecordsAndAcknowledgedRevisionsReuseEvidence() {
  val other=record.copy(id="b")
  assertEquals(WeeklyReviewCache.fingerprint(week,listOf(record,other),today),WeeklyReviewCache.fingerprint(week,listOf(other,record.copy(revision=9)),today))
 }
 @Test fun offlineEditDeletionAndCoverageDateInvalidate() {
  val original=WeeklyReviewCache.fingerprint(week,listOf(record),today)
  assertNotEquals(original,WeeklyReviewCache.fingerprint(week,listOf(record.copy(payload="""{"status":"skipped","scheduled_at":"2026-10-14T10:00:00-05:00"}""")),today))
  assertNotEquals(original,WeeklyReviewCache.fingerprint(week,listOf(record.copy(deleted=true)),today))
  assertNotEquals(original,WeeklyReviewCache.fingerprint(week,emptyList(),today))
  assertNotEquals(original,WeeklyReviewCache.fingerprint(week,listOf(record),today.plusDays(1)))
 }
 @Test fun unrelatedWeeksDoNotInvalidateAndGenerationTimeIsPreserved() {
  val other=record.copy(id="other",payload="""{"status":"done","scheduled_at":"2026-11-01T10:00:00-06:00"}""")
  assertEquals(WeeklyReviewCache.fingerprint(week,listOf(record),today),WeeklyReviewCache.fingerprint(week,listOf(record,other),today))
  val time=java.time.Instant.parse("2026-10-14T12:00:00Z")
  assertEquals(time.toString(),JSONObject(WeeklyReviewCache.encode("x",response(),time)).getString("generated_at"))
 }
 @Test fun finishedWeekDoesNotExpireEveryDay() {
  assertEquals(WeeklyReviewCache.fingerprint(week,listOf(record),week.plusDays(7)),WeeklyReviewCache.fingerprint(week,listOf(record),week.plusDays(30)))
 }
 @Test fun exactSnapshotRoundTripsWithoutLosingCoverageOrProvenance() {
  val fingerprint=WeeklyReviewCache.fingerprint(week,listOf(record),today)
  val encoded=WeeklyReviewCache.encode(fingerprint,response())
  val decoded=WeeklyReviewCache.decode(encoded,fingerprint)!!
  assertEquals("test-model",decoded.getString("model"))
  assertEquals(1,decoded.getJSONObject("coverage").getInt("unlogged_activity_count"))
  assertNull(WeeklyReviewCache.decode(encoded,"different"))
 }
 @Test fun corruptOrOldCacheIsNotPresentedAsSummary() {
  assertNull(WeeklyReviewCache.decode(null,"x"))
  assertNull(WeeklyReviewCache.decode("broken","x"))
  val raw=JSONObject(WeeklyReviewCache.encode("x",response())).put("version",0).toString()
  assertNull(WeeklyReviewCache.decode(raw,"x"))
 }
 @Test fun deterministicEmptyWeekPreservesAbsenceOfModelProvenance() {
  val empty=response().put("summary","No logs for this week.").put("adjustments",org.json.JSONArray())
   .put("generated",false).put("model",JSONObject.NULL).put("provider",JSONObject.NULL)
  val decoded=WeeklyReviewCache.decode(WeeklyReviewCache.encode("empty",empty),"empty")!!
  assertFalse(decoded.getBoolean("generated"));assertTrue(decoded.isNull("model"))
  assertTrue(runCatching{WeeklyReviewCache.validate(response().put("model",JSONObject.NULL))}.isFailure)
 }
 @Test fun invalidSummaryOrCoverageCannotBeCached() {
  val invalid=listOf(response().put("summary",""),response().put("adjustments",org.json.JSONArray().put(JSONObject())),response().apply{getJSONObject("coverage").put("food_days",4)},response().apply{getJSONObject("coverage").put("unlogged_activity_count",-1)},response().apply{getJSONObject("coverage").put("covered_days",2.5)})
  invalid.forEach { assertTrue(runCatching{WeeklyReviewCache.encode("x",it)}.isFailure) }
 }
 @Test fun futureWeeksCannotGenerateAndWrongWeekCacheIsRejected() {
  assertTrue(WeeklyReviewCache.canGenerate(week,today))
  assertFalse(WeeklyReviewCache.canGenerate(week.plusWeeks(1),today))
  val encoded=WeeklyReviewCache.encode("x",response().put("week_start",week.plusWeeks(1).toString()))
  assertNull(WeeklyReviewCache.decode(encoded,"x",week))
  assertTrue(runCatching{WeeklyReviewCache.validate(response().put("week_start",week.plusWeeks(1).toString()),week)}.isFailure)
 }
 @Test fun connectionErrorsAreActionableWhileUnknownErrorsAreRedacted() {
  val known="Connect to your server in Settings to continue"
  assertEquals(known,WeeklyReviewCache.errorMessage(IllegalStateException(known)))
  val missing="The model isn't configured on your server yet"
  assertEquals(missing,WeeklyReviewCache.errorMessage(IllegalStateException(missing)))
  assertEquals("Couldn't generate the wrap-up. Try again.",WeeklyReviewCache.errorMessage(IllegalStateException("secret provider body")))
 }

}
