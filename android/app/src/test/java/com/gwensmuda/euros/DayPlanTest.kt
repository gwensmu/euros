package com.gwensmuda.euros

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DayPlanTest {
 private val date=LocalDate.parse("2026-10-14")
 private fun plan()=JSONObject("""{"schedule":{"phaseId":"active","dayNutritionProfiles":{"WEDNESDAY":"rest"}},"phases":[{"id":"active","start":"2026-10-14","end":"2026-10-20"}],"mealTargets":[{"id":"arbitrary-one","title":"Everyday","appliesWhen":"every_day"},{"id":"unrelated-id","title":"Training","appliesWhen":"bjj_day"},{"id":"unknown-rule","title":"Unknown","appliesWhen":"future_rule"}],"nutrition":{"daily":{"kcal":1900,"proteinG":null,"fatG":null}}}""")
 private fun record(id:String="a",kind:String="bjj",status:String="pending",parent:String="",day:LocalDate=date,deleted:Boolean=false,step:String="")=Record("activities",id,1,JSONObject().put("title",id).put("kind",kind).put("status",status).put("scheduled_at","${day}T08:00:00-05:00").put("parent_id",parent).put("recovery_step_type",step).toString(),deleted)
 private fun ids(day:DayPlan)=day.meals.map{it.id}
 @Test fun eligibilityUsesRulesRatherThanMealNamesOrIds() {
  assertEquals(listOf("arbitrary-one"),ids(DayPlanComposer.compose(plan(),emptyList(),date)))
  assertEquals(listOf("arbitrary-one","unrelated-id"),ids(DayPlanComposer.compose(plan(),listOf(record()),date)))
  assertEquals(listOf("arbitrary-one"),ids(DayPlanComposer.compose(plan(),listOf(record(kind="weights")),date)))
 }
 @Test fun skippedBjjStillHasItsScheduledMealReferences() {
  assertTrue(ids(DayPlanComposer.compose(plan(),listOf(record(status="skipped")),date)).contains("unrelated-id"))
 }
 @Test fun movingOrDeletingSessionChangesDayEligibilityWithoutDeletingHistory() {
  val tomorrow=date.plusDays(1)
  val moved=record(day=tomorrow)
  assertEquals(listOf("arbitrary-one"),ids(DayPlanComposer.compose(plan(),listOf(moved),date)))
  assertTrue(ids(DayPlanComposer.compose(plan(),listOf(moved),tomorrow)).contains("unrelated-id"))
  assertEquals(listOf("arbitrary-one"),ids(DayPlanComposer.compose(plan(),listOf(record(deleted=true)),date)))
 }
 @Test fun phaseBoundariesInclusiveAndOtherPhaseDoesNotActivateReferences() {
  val config=plan()
  assertTrue(DayPlanComposer.inPhase(config,date));assertTrue(DayPlanComposer.inPhase(config,date.plusDays(6)))
  for(day in listOf(date.minusDays(1),date.plusDays(7))) {
   val result=DayPlanComposer.compose(config,listOf(record(day=day)),day)
   assertTrue(result.meals.isEmpty());assertEquals(1900,result.nutrition!!.getInt("kcal"))
   assertEquals(1,result.activities.size) // Existing historical records remain visible.
  }
  config.getJSONObject("schedule").put("phaseId","other")
  assertFalse(DayPlanComposer.inPhase(config,date));assertFalse(DayPlanComposer.inPhase(JSONObject(),date))
 }
 @Test fun eachKindGetsTheCorrectLoggingInteraction() {
  val expected=mapOf("bjj" to LoggingMode.SESSION,"weights" to LoggingMode.COMPLETION,"rest" to LoggingMode.COMPLETION,"morning" to LoggingMode.COMPLETION,"recovery" to LoggingMode.COMPLETION,"food" to LoggingMode.FOOD,"weight" to LoggingMode.WEIGHT,"future" to LoggingMode.UNSUPPORTED,"" to LoggingMode.UNSUPPORTED)
  expected.forEach{(kind,mode)->assertEquals(kind,mode,DayPlanComposer.item(record(kind=kind),emptyList()).logging)}
 }
 @Test fun intakeRequiresRecoveryKindAndChildrenTakePrecedence() {
  listOf("hydration","supplement").forEach { step->
   val parent=record(kind="recovery",step=step)
   assertEquals(if(step=="hydration")LoggingMode.COMPLETION else LoggingMode.INTAKE,DayPlanComposer.item(parent,emptyList()).logging)
   assertEquals(LoggingMode.STEPS,DayPlanComposer.item(parent,listOf(record("child",parent="a"))).logging)
  }
  assertEquals(LoggingMode.COMPLETION,DayPlanComposer.item(record(kind="recovery",step="unknown"),emptyList()).logging)
  assertEquals(LoggingMode.COMPLETION,DayPlanComposer.item(record(kind="weights",step="hydration"),emptyList()).logging)
  assertEquals(LoggingMode.STEPS,DayPlanComposer.item(record(kind="future"),listOf(record("child",parent="a"))).logging)
  assertEquals(LoggingMode.UNSUPPORTED,DayPlanComposer.item(record(kind="future"),listOf(record("child",parent="a",deleted=true))).logging)
 }
 @Test fun dailyNutritionIgnoresSessionsWeekdaysAndLegacyProfiles() {
  val config=plan()
  for(offset in -1L..8L) {
   val day=date.plusDays(offset)
   for(records in listOf(emptyList(),listOf(record(kind="rest",day=day)),listOf(record(kind="bjj",day=day)),listOf(record(day=day.plusDays(1))),listOf(record(deleted=true,day=day)))) {
    val target=DayPlanComposer.compose(config,records,day).nutrition!!
    assertEquals(1900,target.getInt("kcal"));assertTrue(target.isNull("proteinG"));assertTrue(target.isNull("fatG"))
   }
  }
  config.getJSONObject("nutrition").remove("daily")
  config.getJSONObject("nutrition").put("trainingAB",JSONObject().put("kcal",2000))
  assertNull(DayPlanComposer.compose(config,listOf(record()),date).nutrition)
 }
 @Test fun childCompletionDeterminesNextEvenWhenParentWasAlreadyDone() {
  val parent=record(kind="rest",status="done")
  val child=record("child",kind="recovery",parent="a")
  val later=record("z",kind="weights")
  assertEquals("a",DayPlanComposer.compose(plan(),listOf(parent,child,later),date).next!!.record.id)
  for(status in listOf("done","skipped")) {
   val resolved=record("child",kind="recovery",parent="a",status=status)
   assertEquals("z",DayPlanComposer.compose(plan(),listOf(parent,resolved,later),date).next!!.record.id)
  }
  assertNull(DayPlanComposer.compose(plan(),listOf(record(status="done"),record("b",status="skipped")),date).next)
 }
 @Test fun mixedResolvedChildrenDoNotKeepParentNextOrDuplicateTimeline() {
  val records=listOf(record(kind="rest"),record("one",parent="a",status="done"),record("two",parent="a",status="skipped"))
  val result=DayPlanComposer.compose(plan(),records,date)
  assertEquals(1,result.activities.size);assertEquals(2,result.activities.single().steps.size);assertNull(result.next)
 }
 @Test fun bundledRestDayHasDinnerAndIndependentRecoveryWithoutTrainingMeals() {
  val config=JSONObject(java.io.File("src/main/assets/euros.json").readText())
  val commands=JSONArray(java.io.File("src/main/assets/schedule-commands.json").readText())
  val records=(0 until commands.length()).map{commands.getJSONObject(it)}.map{Record("activities",it.getString("entity_id"),0,it.getJSONObject("payload").toString())}
  val result=DayPlanComposer.compose(config,records,date)
  val rest=result.activities.single()
  assertEquals(PlanItemKind.REST,rest.kind);assertEquals(LoggingMode.STEPS,rest.logging)
  assertEquals(4,rest.steps.size);assertEquals(rest,result.next)
  assertTrue(result.meals.any{it.id=="dinner"});assertFalse(result.meals.any{it.appliesWhen=="bjj_day"})
  val dinner=result.meals.single{it.id=="dinner"};assertNull(dinner.protein);assertNull(dinner.carbs);assertNull(dinner.fat)
  assertEquals(1900,result.nutrition!!.getInt("kcal"))
 }
}
