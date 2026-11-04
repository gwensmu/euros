package com.gwensmuda.euros

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.TimeZone

class TravelTimezoneTest {
 private fun inZone(id:String,block:()->Unit) {
  val previous=TimeZone.getDefault()
  try {TimeZone.setDefault(TimeZone.getTimeZone(id));block()} finally {TimeZone.setDefault(previous)}
 }
 private fun activity(at:String)=Record("activities","travel",1,JSONObject().put("kind","rest").put("title","Easy movement").put("status","pending").put("scheduled_at",at).put("time_zone","Europe/Lisbon").toString())
 @Test fun lisbonActivityShowsLocalClockAndMovesInLocalZone() {
  val r=activity("2027-01-09T10:00:00Z")
  inZone("America/Chicago"){assertEquals("4:00 AM",activityTime(r))}
  inZone("Europe/Lisbon") {
   assertEquals("10:00 AM",activityTime(r))
   assertEquals(Instant.parse("2027-01-09T11:00:00Z"),OffsetDateTime.parse(PlanDomain.moveTimestamp("2027-01-09","11:00")).toInstant())
   assertEquals(LocalDate.parse("2027-01-09"),PlanDomain.day(r))
  }
  assertEquals("2027-01-09T10:00:00Z",JSONObject(r.payload).getString("scheduled_at"))
 }
 @Test fun midnightGroupingChangesWithoutChangingCanonicalDate() {
  val r=activity("2027-01-09T00:30:00Z")
  inZone("Europe/Lisbon") {
   assertEquals(LocalDate.parse("2027-01-09"),PlanDomain.day(r))
   assertEquals(LocalDate.parse("2027-01-08"),PlanDomain.day(r,PlanDomain.canonicalZone))
  }
 }
 @Test fun mealAndWeightRemindersUseLisbonWallClockAndQuietHours() {
  inZone("Europe/Lisbon") {
   val day=LocalDate.parse("2027-01-09")
   val meals=listOf(FoodMealReference("breakfast","Breakfast",null,null,null,"08:00",foods=listOf("eggs")))
   val reminders=RichReminderRules.plans(emptyList(),ReminderSettings(true,"22:00","07:00"),day,PlanDomain.zone,meals,emptySet(),emptyMap())
   assertEquals(Instant.parse("2027-01-09T08:30:00Z"),reminders.single{it.kind=="weight"}.due)
   assertEquals(Instant.parse("2027-01-09T08:00:00Z"),reminders.single{it.kind=="morning"}.due)
   assertEquals(Instant.parse("2027-01-10T07:00:00Z"),ReminderRules.afterQuiet(Instant.parse("2027-01-09T23:00:00Z"),ReminderSettings(true,"22:00","07:00"),PlanDomain.zone))
  }
 }
 @Test fun newFoodUsesDeviceZoneButSavedEntryKeepsItsOriginalDate() {
  val r=Record("food_entries","old",1,JSONObject().put("local_date","2027-01-08").put("consumed_at","2027-01-08T23:30:00-06:00").toString())
  inZone("Europe/Lisbon") {
   val d=FoodDomain.newDraft(LocalDate.parse("2027-01-09"),Clock.fixed(Instant.parse("2027-01-09T10:00:00Z"),PlanDomain.zone))
   assertEquals("Europe/Lisbon",d.timeZone)
   assertEquals(1,FoodDomain.entries(listOf(r),LocalDate.parse("2027-01-08")).size)
   assertTrue(FoodDomain.entries(listOf(r),LocalDate.parse("2027-01-09")).isEmpty())
  }
 }
 @Test fun travelDoesNotChangeBackendAlignedMovedBudgets() {
  val p=JSONObject().put("kind","bjj").put("scheduled_at","2027-01-09T00:30:00Z")
   .put("move_history",org.json.JSONArray().put(JSONObject().put("from","2027-01-09T00:00:00Z").put("to","2027-01-09T00:30:00Z").put("adjust_nutrition",true)))
  val r=Record("activities","moved",1,p.toString())
  inZone("Europe/Lisbon") {
   assertEquals("bjj",NutritionPolicy.movedTier(listOf(r),LocalDate.parse("2027-01-08")))
   assertNull(NutritionPolicy.movedTier(listOf(r),LocalDate.parse("2027-01-09")))
   val weight=PlanDomain.weightPayload("155",OffsetDateTime.parse("2027-01-09T00:30:00Z"),"tracker",PlanDomain.canonicalZone)
   assertEquals("2027-01-08",weight.getString("local_date"))
  }
 }
}
