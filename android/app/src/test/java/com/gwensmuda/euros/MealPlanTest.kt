package com.gwensmuda.euros

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MealPlanTest {
 private val day=LocalDate.parse("2026-10-19")
 private fun payload(label:String="A",food:String="Eggs 100 g")=JSONObject().put("date",day.toString()).put("reference_day",label)
  .put("meals",JSONArray().apply{listOf("breakfast","lunch","dinner","recovery-snack").forEach{id->put(JSONObject().put("id",id).put("title",id).put("proteinG",JSONObject.NULL).put("carbohydrateG",JSONObject.NULL).put("fatG",JSONObject.NULL).put("localTime",JSONObject.NULL).put("appliesWhen","every_day").put("foods",JSONArray().put(food)))}})
  .put("nutrition_targets",JSONObject("""{"kcal":1900,"proteinG":130,"carbohydrateG":225,"fatG":{"min":50,"max":53}}"""))
 private fun record(p:JSONObject=payload(),revision:Long=1,deleted:Boolean=false)=Record("meal_plans","meal-plan-$day",revision,p.toString(),deleted)
 private fun fallback()=JSONObject("""{"mealTargets":[{"id":"fallback","title":"Fallback"}],"nutrition":{"daily":{"kcal":1900}}}""")
 @Test fun savedRevisionDrivesMealsAndTargetsWithoutChangingLoggedConsumption() {
  val old=record();val newer=record(payload("B","Yogurt 200 g").apply{getJSONObject("nutrition_targets").put("kcal",2000)},2)
  val entry=FoodDomain.newDraft(day).copy(text="Eggs 100 g",kcal="150",mealReferenceId="breakfast")
  val logged=Record("food_entries",entry.id,1,FoodDomain.payload(entry).toString())
  val records=listOf(old,newer,logged)
  val composed=DayPlanComposer.compose(fallback(),records,day)
  assertEquals(4,composed.meals.size);assertEquals(listOf("Yogurt 200 g",FoodDomain.MEAL_WATER),composed.meals.first().foods)
  assertEquals(2000,composed.nutrition!!.getInt("kcal"))
  assertEquals(3,FoodDomain.remainingReferences(composed.meals,records,day).size)
  assertEquals("150",FoodDomain.summary(records,day).kcal.display());assertEquals("Eggs 100 g",FoodDomain.draftFromRecord(logged).text)
 }
 @Test fun savedPlansAreDateIsolatedAndTombstonesCannotRevealOlderRevision() {
  assertEquals("fallback",FoodDomain.mealReferences(fallback(),day.plusDays(1),listOf(record())).single().id)
  assertEquals("fallback",FoodDomain.mealReferences(fallback(),day,listOf(record(),record(revision=2,deleted=true))).single().id)
 }
 @Test fun savedMealValidationPreservesUnknownsAndRejectsAmbiguousOrInvalidTargets() {
  RecordValidation.validate("meal_plans",payload())
  val duplicate=payload();duplicate.getJSONArray("meals").put(duplicate.getJSONArray("meals").getJSONObject(0))
  assertThrows(IllegalArgumentException::class.java){RecordValidation.validate("meal_plans",duplicate)}
  val badRange=payload();badRange.getJSONObject("nutrition_targets").put("fatG",JSONObject().put("min",60).put("max",50))
  assertThrows(IllegalArgumentException::class.java){RecordValidation.validate("meal_plans",badRange)}
 }
}
