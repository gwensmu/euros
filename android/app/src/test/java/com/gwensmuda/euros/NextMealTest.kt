package com.gwensmuda.euros
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class NextMealTest {
 private val day=LocalDate.parse("2026-10-15")
 private val meals=listOf("breakfast" to "08:00","lunch" to "12:30","dinner" to "19:30").map{(id,time)->FoodMealReference(id,id,null,null,null,time)}
 @Test fun nextUpcomingMealSkipsPastTimesAndDismissedMeals() {
  assertEquals("lunch",FoodDomain.nextMeal(meals,emptyList(),day,emptySet(),LocalTime.NOON)!!.id)
  assertEquals("dinner",FoodDomain.nextMeal(meals,emptyList(),day,setOf("lunch"),LocalTime.NOON)!!.id)
 }
 @Test fun noRemainingMealHidesCardAndUnscheduledMealIsFallback() {
  assertNull(FoodDomain.nextMeal(meals,emptyList(),day,meals.map{it.id}.toSet(),LocalTime.NOON))
  val loose=meals.first().copy(time=null)
  assertEquals(loose,FoodDomain.nextMeal(listOf(loose),emptyList(),day,emptySet(),LocalTime.NOON))
 }
}
