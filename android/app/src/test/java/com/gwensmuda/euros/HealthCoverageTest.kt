package com.gwensmuda.euros
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test
class HealthCoverageTest {
 private val start=Instant.parse("2026-10-14T00:00:00Z")
 @Test fun missingSamplesAreZeroCoverageNotAHeartRate() { assertEquals(0L,HealthCoverage.coveredSeconds(start,start.plusSeconds(100),emptyList())) }
 @Test fun gapsAndDuplicateSamplesDoNotInflateCoverage() {
  val samples=listOf(-10L,0,30,30,60,121,151,300).map{start.plusSeconds(it)}
  assertEquals(90L,HealthCoverage.coveredSeconds(start,start.plusSeconds(200),samples))
 }
 @Test fun noExtrapolationFromSingleSample() { assertEquals(0L,HealthCoverage.coveredSeconds(start,start.plusSeconds(100),listOf(start.plusSeconds(10)))) }
 @Test fun reversedWindowHasNoCoverage() { assertEquals(0L,HealthCoverage.coveredSeconds(start,start.minusSeconds(1),listOf(start))) }
}
