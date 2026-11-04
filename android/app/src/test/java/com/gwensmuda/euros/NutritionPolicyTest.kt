package com.gwensmuda.euros
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class NutritionPolicyTest {
 private val plan=JSONObject(java.io.File("../../plan/euros.json").readText())
 private val today=LocalDate.parse("2026-10-20")
 private fun weight(date:String,lb:String,deleted:Boolean=false)=Record("weight_entries",date,1,JSONObject().put("local_date",date).put("weight_lb",lb).toString(),deleted)
 @Test fun thresholdIsInclusiveAndHistoricalTargetsStayUnchanged() {
  val records=listOf(weight("2026-10-18","155"),weight("2026-10-19","157"))
  assertEquals(1900,NutritionPolicy.targets(plan,records,LocalDate.parse("2026-10-17"),today)!!.getInt("kcal"))
  assertEquals(2050,NutritionPolicy.targets(plan,records,today,today)!!.getInt("kcal"))
  assertEquals(LocalDate.parse("2026-10-18"),NutritionPolicy.maintenanceStart(plan,records,today))
 }
 @Test fun oldFutureDeletedAndAboveThresholdCannotTrigger() {
  val records=listOf(weight("2026-10-14","150"),weight("2026-10-21","150"),weight("2026-10-18","155",true),weight("2026-10-19","155.01"))
  assertNull(NutritionPolicy.maintenanceStart(plan,records,today))
  assertEquals(1900,NutritionPolicy.targets(plan,records,today,today)!!.getInt("kcal"))
 }
 @Test fun correctingOnlyQualifyingMeasurementRemovesTransition() {
  assertNotNull(NutritionPolicy.maintenanceStart(plan,listOf(weight("2026-10-18","154.9")),today))
  assertNull(NutritionPolicy.maintenanceStart(plan,listOf(weight("2026-10-18","159.4")),today))
 }
 @Test fun sevenDailyWeightsRequiredAndCorrectionsRecompute() {
  val now=LocalDate.parse("2026-10-29")
  val weights=(22..28).map{weight("2026-10-$it","155")}
  assertNull(NutritionPolicy.maintenanceStart(plan,weights.dropLast(1),now))
  assertEquals(LocalDate.parse("2026-10-28"),NutritionPolicy.maintenanceStart(plan,weights+weight("2026-10-29","160"),now))
  assertEquals(2050,NutritionPolicy.targets(plan,weights,now,now)!!.getInt("kcal"))
  assertNull(NutritionPolicy.maintenanceStart(plan,weights.filterIndexed{i,_->i!=2},now))
  assertNull(NutritionPolicy.maintenanceStart(plan,List(7){weight("2026-10-22","154")},now))
 }
 private fun training(id:String,kind:String,day:String,moved:Boolean=false,status:String="pending")=Record("activities",id,1,JSONObject().put("kind",kind).put("status",status).put("scheduled_at","${day}T09:00:00-05:00").apply {
  if(moved)put("move_history",org.json.JSONArray().put(JSONObject().put("from","2026-10-30T09:00:00-05:00").put("to","2026-10-31T09:00:00-05:00").put("adjust_nutrition",true)))
 }.toString(),false)
 @Test fun movesUpdateBothBudgetsWithoutSkipDebtOrFoodMutation() {
  val moved=training("a","bjj","2026-10-31",true)
  val records=listOf(moved,training("b","weights","2026-10-30"),training("c","weights","2026-10-31",status="skipped"))
  val before=records.map{it.payload}
  fun calories(day:String,rs:List<Record> = records)=NutritionPolicy.targets(plan,rs,LocalDate.parse(day),LocalDate.parse("2026-10-31"))!!.getInt("kcal")
  assertEquals(2050,calories("2026-10-30"))
  assertEquals(2550,calories("2026-10-31"))
  assertEquals(1850,calories("2026-10-30",listOf(moved)))
  assertEquals(2300,calories("2026-10-31",listOf(moved)))
  assertEquals(2500,calories("2026-10-31",listOf(moved)+(22..28).map{weight("2026-10-$it","155")}))
  assertEquals(before,records.map{it.payload})
  assertNull(NutritionPolicy.movedTier(listOf(training("a","bjj","2026-10-31")),LocalDate.parse("2026-10-31")))
 }
}
