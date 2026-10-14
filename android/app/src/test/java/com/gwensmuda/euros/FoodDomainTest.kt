package com.gwensmuda.euros

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class FoodDomainTest {
 private val date=LocalDate.of(2026,10,14)
 private fun draft()=FoodDraft("stable-food","2026-10-14T12:30:00-05:00",text="Rice and chicken",grams="350",kcal="500",protein="35",carbs="70",fat="8")
 private fun record(id:String="stable-food",revision:Long=1,deleted:Boolean=false,value:FoodDraft=draft())=Record("food_entries",id,revision,FoodDomain.payload(value).toString(),deleted)
 @Test fun decimalNutrientsAddWithoutBinaryRounding() {
  val records=listOf(record("one",value=draft().copy(kcal="0.1")),record("two",value=draft().copy(kcal="0.2")))
  assertEquals(BigDecimal("0.3"),FoodDomain.summary(records,date).kcal.known)
 }
 @Test fun serverNumericExponentNotationIsNotMistakenForUnknown() {
  val item=record();val payload=JSONObject(item.payload)
  payload.getJSONObject("nutrients").put("fat_g",0.0001)
  val result=FoodDomain.summary(listOf(item.copy(payload=payload.toString())),date)
  assertEquals(0,BigDecimal("0.0001").compareTo(result.fat.known));assertEquals(0,result.fat.missingCount)
 }
 @Test fun explicitZeroDiffersFromUnknown() {
  val zero=FoodDomain.summary(listOf(record(value=draft().copy(kcal="0",fat=""))),date)
  assertEquals("0",zero.kcal.display());assertEquals("—",zero.fat.display());assertEquals(1,zero.fat.missingCount)
  assertEquals("—",FoodDomain.summary(emptyList(),date).kcal.display())
 }
 @Test fun partialTotalsRetainKnownSubtotal() {
  val result=FoodDomain.summary(listOf(record("known"),record("unknown",value=draft().copy(kcal=""))),date)
  assertEquals(BigDecimal("500"),result.kcal.known);assertEquals(1,result.kcal.missingCount);assertEquals(2,result.entries)
 }
 @Test fun correctedEntryReplacesContributionAndNeverDuplicates() {
  val old=record(revision=1)
  val corrected=record(revision=2,value=draft().copy(kcal="550"))
  val result=FoodDomain.summary(listOf(corrected,old),date)
  assertEquals(1,result.entries);assertEquals(BigDecimal("550"),result.kcal.known)
 }
 @Test fun tombstoneWinsAndOtherDatesDoNotContribute() {
  val result=FoodDomain.summary(listOf(record(),record(revision=2,deleted=true),record("other",value=draft().copy(consumedAt="2026-10-15T00:05:00-05:00",dateInput="2026-10-15",timeInput="00:05"))),date)
  assertEquals(0,result.entries);assertNull(result.kcal.known)
 }
 @Test fun bodyDateIsDerivedFromActualOffsetInsteadOfUtc() {
  val value=draft().copy(consumedAt="2026-10-14T23:30:00-05:00",timeInput="23:30")
  assertEquals("2026-10-14",FoodDomain.payload(value).getString("local_date"))
 }
 @Test fun draftRoundTripRetainsIdentityUnknownsAndOriginalSnapshot() {
  val value=draft().copy(fat="",mealReferenceId="lunch",plannedActivityId="scheduled-meal",originalPayload="""{"source":{"id":"source-1"}}""")
  val restored=FoodDomain.loadDraft(FoodDomain.saveDraft(value))
  assertEquals(value,restored);assertEquals("stable-food",restored.id)
 }
 @Test fun correctingFoodPreservesOriginalSourceAndExplicitZero() {
  val value=draft().copy(kcal="0",fat="",originalPayload="""{"source":{"id":"original"},"nutrition_snapshot":{"revision":3}}""")
  val payload=FoodDomain.payload(value)
  assertEquals("original",payload.getJSONObject("source").getString("id"))
  assertEquals(3,payload.getJSONObject("nutrition_snapshot").getInt("revision"))
  assertEquals("0",payload.getJSONObject("nutrients").getString("kcal"));assertTrue(payload.getJSONObject("nutrients").isNull("fat_g"))
 }
 @Test fun quantitiesNeverDriveInventedNutrition() {
  val first=FoodDomain.payload(draft().copy(grams="100"))
  val second=FoodDomain.payload(draft().copy(grams="200"))
  assertEquals(first.getJSONObject("nutrients").toString(),second.getJSONObject("nutrients").toString())
 }
 @Test fun unknownNutritionCanBeSavedExplicitlyWithoutTurningIntoZeros() {
  val payload=FoodDomain.payload(draft().copy(grams="",kcal="",protein="",carbs="",fat=""))
  assertTrue(payload.isNull("quantity_g"))
  for(key in listOf("kcal","protein_g","carbs_g","fat_g")) assertTrue(payload.getJSONObject("nutrients").isNull(key))
 }
 @Test fun rejectsInvalidValuesAndAllowsFractionalGrams() {
  for(value in listOf("-1","NaN","Infinity","1,000","1e5","0.0000001","200001")) assertTrue(value,FoodDomain.validate(draft().copy(kcal=value)).containsKey("kcal"))
  for(value in listOf("0","0.0","-1")) assertTrue(value,FoodDomain.validate(draft().copy(grams=value)).containsKey("grams"))
  assertTrue(FoodDomain.validate(draft().copy(grams=".5",kcal="0.25")).isEmpty())
 }
 @Test fun blankTextCannotBeCommitted() {assertTrue(FoodDomain.validate(draft().copy(text="  ")).containsKey("text"))}
 @Test fun invalidTimestampCannotBeCommitted() {assertTrue(FoodDomain.validate(draft().copy(consumedAt="yesterday")).containsKey("time"))}
 @Test fun displayRoundsHalfUpOnlyAtPresentation() {
  val totals=FoodDomain.summary(listOf(record(value=draft().copy(kcal="500.5"))),date)
  assertEquals(BigDecimal("500.5"),totals.kcal.known);assertEquals("501",totals.kcal.display())
 }
 @Test fun mealReferenceCompletionIsExplicitNotInferredFromFoodName() {
  val meals=listOf(FoodMealReference("lunch","Lunch","35","55",null,null))
  assertEquals(meals,FoodDomain.remainingReferences(meals,listOf(record(value=draft().copy(text="Lunch"))),date))
  assertTrue(FoodDomain.remainingReferences(meals,listOf(record(value=draft().copy(mealReferenceId="lunch"))),date).isEmpty())
 }
 @Test fun referenceRangesAndMissingValuesRemainVisibleWithoutInventedFoods() {
  val plan=JSONObject("""{"mealTargets":[{"id":"after","title":"Post-training meal","proteinG":35,"carbohydrateG":{"min":65,"max":70},"fatG":null,"localTime":null,"foods":[]}]}""")
  val meal=FoodDomain.mealReferences(plan).single()
  assertEquals("65–70",meal.carbs);assertNull(meal.fat);assertNull(meal.time)
 }
 @Test fun newDraftUsesRequestedLocalDateAndRealZoneOffset() {
  val clock=Clock.fixed(Instant.parse("2026-11-02T18:00:00Z"),ZoneId.of("America/Chicago"))
  val new=FoodDomain.newDraft(LocalDate.of(2026,11,1),clock)
  assertEquals("2026-11-01T12:00-06:00",new.consumedAt)
  assertTrue(new.id.isNotBlank())
 }
 @Test fun draftKeepsClockZoneWhenDateIsEdited() {
  val clock=Clock.fixed(Instant.parse("2026-10-14T18:00:00Z"),ZoneId.of("America/Los_Angeles"))
  val original=FoodDomain.newDraft(date,clock).copy(text="Lunch")
  assertEquals("America/Los_Angeles",original.timeZone)
  val edited=FoodDomain.loadDraft(FoodDomain.saveDraft(original)).copy(dateInput="2026-11-02")
  assertEquals("2026-11-02T11:00-08:00",FoodDomain.timestamp(edited))
 }
 @Test fun backfillDateResolvesChicagoOffsetAcrossDst() {
  val value=draft().copy(dateInput="2026-11-02",timeInput="12:30")
  assertEquals("2026-11-02T12:30-06:00",FoodDomain.payload(value).getString("consumed_at"))
  assertEquals("2026-11-02",FoodDomain.payload(value).getString("local_date"))
 }
 @Test fun invalidAndAmbiguousClockEditsAreNotSilentlyShifted() {
  assertTrue(FoodDomain.validate(draft().copy(dateInput="2026-03-08",timeInput="02:30")).containsKey("time"))
  assertTrue(FoodDomain.validate(draft().copy(dateInput="2026-11-01",timeInput="01:30")).containsKey("time"))
 }
 @Test fun partialDateInputSurvivesDraftRoundTripWithoutInventingTimestamp() {
  val value=draft().copy(dateInput="2026-11-",timeInput="1:")
  assertEquals(value,FoodDomain.loadDraft(FoodDomain.saveDraft(value)))
  assertTrue(FoodDomain.validate(value).containsKey("time"))
 }
 @Test fun originalTimestampPrecisionAndOffsetRemainWhenTimeIsUnchanged() {
  val value=FoodDraft("original","2026-11-01T01:30:44.123-06:00",text="Breakfast")
  assertEquals(value.consumedAt,FoodDomain.timestamp(value))
 }
 @Test fun editDraftRetainsAcceptedRevisionForAtomicStaleEditCheck() {
  val editing=FoodDomain.draftFromRecord(record(revision=7))
  assertEquals(7L,editing.baseRevision)
  assertEquals(7L,FoodDomain.loadDraft(FoodDomain.saveDraft(editing)).baseRevision)
 }
 @Test fun sameDraftProducesSamePayloadAcrossRetry() {
  val saved=FoodDomain.loadDraft(FoodDomain.saveDraft(draft()))
  assertEquals(draft().id,saved.id);assertEquals(FoodDomain.payload(draft()).toString(),FoodDomain.payload(saved).toString())
 }
 @Test fun bundledDinnerIsSeparateFromTrainingAndHasNoInventedAllocation() {
  val bundled=java.io.File("src/main/assets/euros.json").readText()
  assertEquals(java.io.File("../../plan/euros.json").readText(),bundled)
  val meals=FoodDomain.mealReferences(JSONObject(bundled))
  val dinner=meals.single{it.id=="dinner"}
  assertEquals("Dinner",dinner.title)
  assertNull(dinner.protein);assertNull(dinner.carbs);assertNull(dinner.fat);assertNull(dinner.time)
  assertEquals("65–70",meals.single{it.id=="post-training"}.carbs)
  for(day in listOf(LocalDate.of(2026,10,14),LocalDate.of(2026,10,17))) {
   assertTrue(FoodDomain.remainingReferences(meals,emptyList(),day).any{it.id=="dinner"})
   val training=record(value=draft().copy(consumedAt="${day}T19:30:00-05:00",dateInput=day.toString(),mealReferenceId="post-training"))
   assertTrue(FoodDomain.remainingReferences(meals,listOf(training),day).any{it.id=="dinner"})
   val eaten=record(value=draft().copy(consumedAt="${day}T19:30:00-05:00",dateInput=day.toString(),mealReferenceId="dinner"))
   assertFalse(FoodDomain.remainingReferences(meals,listOf(eaten),day).any{it.id=="dinner"})
  }
 }
}
