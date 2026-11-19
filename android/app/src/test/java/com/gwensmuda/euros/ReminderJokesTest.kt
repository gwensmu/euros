package com.gwensmuda.euros

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class ReminderJokesTest {
 private val kinds=listOf("morning","weight","bedtime","prep","training","recovery","food","water")

 @Test fun everyReminderKindHasLines() {
  for(kind in kinds)assertTrue(kind,ReminderJokes.options(kind,false,false).size>=4)
 }

 @Test fun idsAreUniqueAndLinesAreShort() {
  val all=ReminderJokes.catalog.values.flatten()
  assertEquals(all.size,all.map{it.id}.toSet().size)
  assertFalse(all.any{it.id=="weasel_out"})
  all.forEach{assertTrue(it.id,it.text.isNotBlank() && it.text.length<=120)}
 }

 @Test fun theSkipLineNeedsAnExplicitSkip() {
  assertFalse(ReminderJokes.options("training",false,false).any{it.id=="weasel_out"})
  assertTrue(ReminderJokes.options("training",true,false).any{it.id=="weasel_out"})
  // Only training reminders cite a logged skip.
  assertFalse(ReminderJokes.options("recovery",true,false).any{it.id=="weasel_out"})
 }

 @Test fun sundayMorningsAddSundayLines() {
  val weekday=ReminderJokes.options("morning",false,false).map{it.id}
  val sunday=ReminderJokes.options("morning",false,true).map{it.id}
  assertTrue("scale" !in weekday && "scale" in sunday && sunday.containsAll(weekday))
 }

 @Test fun noRepeatUntilEveryLineHasBeenUsed() {
  val random=Random(7);var recent=listOf<String>()
  val options=ReminderJokes.options("water",false,false)
  val seen=(options.indices).map{
   val pick=ReminderJokes.choose("water",false,false,recent,random)!!
   recent=listOf(pick.id)+recent;pick.id
  }
  assertEquals(options.map{it.id}.toSet(),seen.toSet())
  // All used recently: fall back to the one used longest ago, not a fresh repeat.
  assertEquals(seen.first(),ReminderJokes.choose("water",false,false,recent,random)!!.id)
 }

 @Test fun selectionIsRandom() {
  val picks=(1..50).map{ReminderJokes.choose("training",false,false,emptyList(),Random(it))!!.id}.toSet()
  assertTrue(picks.size>1)
 }

 @Test fun unknownKindsGetNoLine() {
  assertNull(ReminderJokes.choose("unknown",false,false,emptyList()))
 }
}
