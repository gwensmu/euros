package com.gwensmuda.euros

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class CompetitionCountdownTest {
 private val start=LocalDate.of(2026,10,14)
 private val event=LocalDate.of(2027,1,13)
 @Test fun calendarProgressClampsAtBothEnds() {
  assertEquals(0f,CompetitionCountdown.fraction(start,event,start.minusDays(1)))
  assertEquals(0f,CompetitionCountdown.fraction(start,event,start))
  assertEquals(29f/91f,CompetitionCountdown.fraction(start,event,LocalDate.of(2026,11,12)),0.00001f)
  assertEquals(1f,CompetitionCountdown.fraction(start,event,event))
  assertEquals(1f,CompetitionCountdown.fraction(start,event,event.plusDays(1)))
 }
 @Test fun absentOrReversedWindowStaysFinite() {
  assertEquals(0f,CompetitionCountdown.fraction(event,event,event.minusDays(1)))
  assertEquals(1f,CompetitionCountdown.fraction(event,event,event))
  assertEquals(0f,CompetitionCountdown.fraction(event.plusDays(1),event,start))
 }
}
