package com.gwensmuda.euros

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class NutritionExportTest {
 private fun food()=Record("food_entries","food-one",1,"""{"consumed_at":"2027-01-09T12:30:00+00:00","raw_text":"Lunch","nutrients":{"kcal":500,"protein_g":30,"carbs_g":null,"fat_g":0}}""")
 @Test fun preservesKnownValuesMissingValuesAndConsumedInstant() {
  val r=NutritionExport.record(food(),12)
  assertEquals(Instant.parse("2027-01-09T12:30:00Z"),r.startTime)
  assertEquals(500.0,r.energy!!.inKilocalories,0.0)
  assertEquals(30.0,r.protein!!.inGrams,0.0)
  assertNull(r.totalCarbohydrate)
  assertEquals(0.0,r.totalFat!!.inGrams,0.0)
  assertEquals("euros-food-food-one",r.metadata.clientRecordId)
  assertEquals(12L,r.metadata.clientRecordVersion)
  assertEquals(1L,java.time.Duration.between(r.startTime,r.endTime).toMillis())
 }
 @Test fun subMillisecondTimesUsePlatformPrecisionWithoutChangingTheSavedLog() {
  val saved=food().copy(payload=food().payload.replace("12:30:00+00:00","12:30:00.123456+00:00"))
  assertEquals(Instant.parse("2027-01-09T12:30:00.123Z"),NutritionExport.record(saved,1).startTime)
  assertTrue(saved.payload.contains(".123456"))
 }
 @Test fun crashRetryReusesVersionAndEditsAdvanceDespiteClockGoingBackwards() {
  val first=NutritionExport.envelope(food(),null,100)
  assertFalse(first.getBoolean("acknowledged"))
  assertSame(first,NutritionExport.envelope(food(),first,200))
  val edited=food().copy(payload=food().payload.replace("500","600"))
  val next=NutritionExport.envelope(edited,first,10)
  assertEquals(101L,next.getLong("version"))
  assertNotEquals(first.getString("fingerprint"),next.getString("fingerprint"))
  assertEquals(NutritionExport.clientId(food().id),NutritionExport.record(edited,101).metadata.clientRecordId)
 }
 @Test fun serverAcknowledgementDoesNotRepublishSameFoodButDeletionDoes() {
  val first=NutritionExport.envelope(food(),null,100).put("acknowledged",true)
  assertTrue(NutritionExport.envelope(food().copy(revision=3),first,200).getBoolean("acknowledged"))
  val deleted=NutritionExport.envelope(food().copy(deleted=true),first,200)
  assertFalse(deleted.getBoolean("acknowledged"))
 }
 @Test fun refusesPlansAndDeletedFoodAsNutritionRecords() {
  assertTrue(runCatching{NutritionExport.record(food().copy(type="meal_plans"),1)}.isFailure)
  assertTrue(runCatching{NutritionExport.record(food().copy(deleted=true),1)}.isFailure)
 }
}
