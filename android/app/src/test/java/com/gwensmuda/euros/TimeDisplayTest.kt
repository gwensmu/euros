package com.gwensmuda.euros
import org.junit.Assert.*
import org.junit.Test

class TimeDisplayTest {
 @Test fun midnightNoonAndEveningDisplayWithoutChangingStoredHour() {
  for((hour,label) in listOf(0 to "12:30 AM",12 to "12:30 PM",18 to "6:30 PM",23 to "11:30 PM")) {
   val stored=TimeDisplay.stored(hour,30)
   assertEquals(hour,java.time.LocalTime.parse(stored).hour)
   assertEquals(label,TimeDisplay.label(stored))
  }
 }
 @Test fun emptyAndInvalidValuesRemainVisibleWithoutCrashing() {
  assertEquals("",TimeDisplay.label(""));assertEquals("invalid",TimeDisplay.label("invalid"))
 }
}
