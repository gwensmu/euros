package com.gwensmuda.euros

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class PlanPresentationTest {
 private fun activity(template:JSONObject)=Record("activities","a",1,JSONObject().put("plan_snapshot",JSONObject().put("template",template)).toString())
 @Test fun sourceInstructionsShownAndUnknownPrescriptionsOmitted() {
  assertEquals("Eight to ten minutes.",PlanDisplay.detail(activity(JSONObject().put("instructions","Eight to ten minutes."))))
  assertNull(PlanDisplay.detail(activity(JSONObject().put("durationMinutes",JSONObject.NULL))))
  assertEquals("45 min",PlanDisplay.detail(activity(JSONObject().put("durationMinutes",45))))
  assertEquals("",PlanDisplay.exercise(JSONObject().put("name","Hip thrust").put("sets",JSONObject.NULL).put("loadLb",JSONObject.NULL)))
  assertEquals("3 × 8 · 20 lb",PlanDisplay.exercise(JSONObject().put("sets",3).put("reps",8).put("loadLb",20)))
 }
 @Test fun weekStartsExpandedAtTodayOnlyWhenInSelectedWeek() {
  val week=LocalDate.parse("2026-10-12")
  assertEquals("2026-10-14",PlanDisplay.initialExpanded(week,LocalDate.parse("2026-10-14")))
  assertEquals("2026-10-12",PlanDisplay.initialExpanded(week,LocalDate.parse("2026-11-01")))
 }
 @Test fun unknownTrackerIsNotMislabelledPolar() {
  assertEquals("BJJ",PlanDisplay.trackerName("fi.polar.polarflow",44))
  assertEquals("Polar workout",PlanDisplay.trackerName("fi.polar.polarflow",53))
  assertEquals("Fitbit workout",PlanDisplay.trackerName("com.fitbit.FitbitMobile",44))
  assertEquals("Fitbit workout",PlanDisplay.trackerName("com.fitbit.FitbitMobile"))
  assertEquals("Polar workout",PlanDisplay.trackerName("fi.polar.polarflow"))
  assertEquals("Tracker workout",PlanDisplay.trackerName("other.app"))
 }
 @Test fun trackerSummaryUsesMeasuredIntensityAndUnknownHr() {
  val start=java.time.Instant.parse("2026-10-14T12:00:00Z")
  val session=ImportedSession("polar",HealthSources.POLAR,start,start.plusSeconds(600),44)
  assertEquals("BJJ",PlanDisplay.trackerSummary(session,null))
  val recap=SessionRecap(session,emptyList(),600.0,128.6,172,listOf(0.0,0.0,360.0,120.0,60.0,30.0,30.0))
  assertEquals("BJJ · Light",PlanDisplay.trackerSummary(session,recap))
  assertEquals("BJJ · Moderate",PlanDisplay.trackerSummary(session,recap.copy(averageBpm=130.0)))
  assertEquals("BJJ · Moderate",PlanDisplay.trackerSummary(session,recap.copy(averageBpm=154.9)))
  assertEquals("BJJ · Intense",PlanDisplay.trackerSummary(session,recap.copy(averageBpm=155.0)))
  assertEquals("BJJ",PlanDisplay.trackerSummary(session,recap.copy(coveredSeconds=479.0)))
  val longSession=session.copy(end=start.plusSeconds(7200))
  fun rounds(seconds:Double)=recap.copy(session=longSession,coveredSeconds=7000.0,zones=listOf(0.0,0.0,7000.0-seconds,0.0,seconds,0.0,0.0))
  assertEquals("BJJ · Light",PlanDisplay.trackerSummary(longSession,rounds(299.0)))
  assertEquals("BJJ · Moderate",PlanDisplay.trackerSummary(longSession,rounds(300.0)))
  assertEquals("BJJ · Moderate",PlanDisplay.trackerSummary(longSession,rounds(1199.0)))
  assertEquals("BJJ · Intense",PlanDisplay.trackerSummary(longSession,rounds(1200.0)))
  assertEquals("BJJ · Intense",PlanDisplay.trackerSummary(longSession,rounds(1322.0)))
 }
 private fun weight(id:String,day:String,anchor:String?,value:String="164.2",deleted:Boolean=false)=Record("weight_entries",id,1,JSONObject().put("measured_at","${day}T08:00:00-05:00").put("weight_lb",value).apply{if(anchor!=null)put("weekly_anchor",anchor)}.toString(),deleted)
 @Test fun weeklyGraphUsesOnlyLatestTwoSelectedMeasurementsNotFutureOrExtraReadings() {
  val records=listOf(weight("old","2026-10-04","2026-09-28"),weight("previous","2026-10-11","2026-10-05"),weight("selected","2026-10-18","2026-10-12"),weight("extra","2026-10-17",null),weight("future","2026-10-25","2026-10-19"),weight("deleted","2026-10-18","2026-10-12",deleted=true))
  assertEquals(listOf("previous","selected"),WeightDisplay.weeklyRecords(records,LocalDate.parse("2026-10-12")).map{it.id})
 }
 @Test fun weightGraphDoesNotFabricateMissingOrInvalidPoints() {
  val records=listOf(weight("one","2026-10-04",null),weight("two","2026-10-18",null),weight("bad","2026-10-11",null,"NaN"))
  val points=WeightDisplay.points(records)
  assertEquals(2,points.size)
  assertEquals(14,java.time.Duration.between(points.first().instant,points.last().instant).toDays().toInt())
 }
 @Test fun coverageNeverIncludesFutureDates() {
  val week=LocalDate.parse("2026-10-12")
  assertEquals(3,WeightDisplay.coveredDates(week,week.plusDays(2)).size)
  assertEquals(7,WeightDisplay.coveredDates(week,week.plusWeeks(1)).size)
  assertTrue(WeightDisplay.coveredDates(week,week.minusDays(1)).isEmpty())
 }
}
