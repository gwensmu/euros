package com.gwensmuda.euros
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class HomeHeadingAndWaterTest {
 private val today=LocalDate.parse("2026-10-15")
 private fun activity(day:LocalDate,status:String="pending")=Record("activities",day.toString(),1,JSONObject().put("kind","bjj").put("title","Class").put("scheduled_at","${day}T18:00:00-05:00").put("status",status).toString())
 @Test fun headingsAndFutureSelectionFollowScheduledDay() {
  assertEquals("Today",PlanDisplay.dateHeading(today,today));assertEquals("Tomorrow",PlanDisplay.dateHeading(today.plusDays(1),today));assertEquals("Wednesday",PlanDisplay.dateHeading(LocalDate.parse("2026-10-21"),today))
  val future=activity(today.plusDays(1));assertEquals(future,PlanDisplay.nextHomeActivity(listOf(activity(today,"done"),future,activity(today.minusDays(1))),today))
 }
 @Test fun waterAppearsOnceWithoutChangingFoodTotals() {
  assertEquals(listOf("watermelon",FoodDomain.MEAL_WATER),FoodDomain.withMealWater(listOf("watermelon")))
  assertEquals(listOf("eggs",FoodDomain.MEAL_WATER),FoodDomain.withMealWater(listOf("eggs")))
  assertEquals(listOf(FoodDomain.MEAL_WATER),FoodDomain.withMealWater(listOf(FoodDomain.MEAL_WATER)))
 }
 @Test fun mealWaterSchedulesMergeAndDismissalCancels() {
  val meals=listOf("breakfast" to "08:00","lunch" to "12:30","dinner" to "19:30","snack" to "21:30").map{(id,time)->FoodMealReference(id,id,null,null,null,time,foods=listOf("food"))}
  fun plans(dismissed:Set<String>)=RichReminderRules.plans(emptyList(),ReminderSettings(true),today,PlanDomain.zone,meals,dismissed,emptyMap())
  val all=plans(emptySet())
  assertEquals(2,all.count{it.kind=="water"})
  assertTrue(all.single{it.kind=="morning"}.facts.contains("water"))
  assertTrue(all.single{it.kind=="food"}.facts.contains("water"))
  assertFalse(plans(setOf("lunch")).any{it.id=="water:$today:lunch"})
  assertEquals(0,plans(meals.map{it.id}.toSet()).count{it.kind=="water"})
 }
}
