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
}
