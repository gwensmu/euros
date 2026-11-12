package com.gwensmuda.euros
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
class DisplayProjectionTest {
 @Test fun foodAndTodayLightweightProjectionsPreserveMealsAndTargets() {
  val plan=org.json.JSONObject(java.io.File("src/main/assets/euros.json").readText())
  val date=LocalDate.parse("2026-11-12")
  val activity=Record("activities","bjj",1,"""{"kind":"bjj","title":"Class","status":"pending","scheduled_at":"2026-11-12T18:30:00-06:00"}""")
  val records=listOf(activity)
  val full=DayPlanComposer.compose(plan,records,date)
  val light=DayPlanComposer.compose(plan,records,date,includeNutrition=false)
  assertEquals(full.activities,light.activities);assertEquals(full.meals,light.meals);assertNull(light.nutrition)
  val food=DayPlanComposer.food(plan,records,date)
  assertEquals(full.meals,food.meals);assertEquals(full.nutrition.toString(),food.nutrition.toString())
  assertEquals(FoodDomain.mealReferences(plan,date,records),food.choices)
 }
 @Test fun sharedEntryProjectionPreservesUnknownTotalsAndDismissal() {
  val date=LocalDate.parse("2026-11-12")
  val entry=Record("food_entries","food",1,"""{"local_date":"2026-11-12","consumed_at":"2026-11-12T12:00:00-06:00","meal_reference_id":"lunch","nutrients":{"kcal":300,"protein_g":null,"carbs_g":20,"fat_g":10}}""")
  val records=listOf(entry);val entries=FoodDomain.entries(records,date)
  assertEquals(FoodDomain.summary(records,date),FoodDomain.summaryFromEntries(entries))
  val refs=listOf(FoodMealReference("lunch","Lunch",null,null,null,null),FoodMealReference("dinner","Dinner",null,null,null,null))
  assertEquals(FoodDomain.remainingReferences(refs,records,date,setOf("dinner")),FoodDomain.remainingReferencesFromEntries(refs,entries,setOf("dinner")))
 }
}
