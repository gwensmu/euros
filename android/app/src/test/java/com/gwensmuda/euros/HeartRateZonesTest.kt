package com.gwensmuda.euros
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
class HeartRateZonesTest {
 private val start=Instant.parse("2026-10-12T13:00:00Z")
 private val session=ImportedSession("test",HealthSources.POLAR,start,start.plusSeconds(600),44)
 @Test fun boundariesAndOutOfRange() {
  val values=listOf(94L,95L,114L,133L,152L,171L,190L,191L,100L)
  val r=HeartRateZones.recap(session,values.mapIndexed{i,bpm->HeartSample(start.plusSeconds(i*10L),bpm)})
  assertEquals(listOf(10.0,10.0,10.0,10.0,10.0,10.0,20.0),r.zones)
  assertEquals(80.0,r.coveredSeconds,0.0)
 }
 @Test fun fractionalGapAndNoExtrapolation() {
  val r=HeartRateZones.recap(session,listOf(HeartSample(start.plusMillis(500),95),HeartSample(start.plusMillis(60500),114),HeartSample(start.plusMillis(120501),171)))
  assertEquals(60.0,r.coveredSeconds,0.0);assertEquals(60.0,r.zones[1],0.0)
  assertEquals(95.0,r.averageBpm!!,0.0)
 }
 @Test fun emptyOrSingleSampleHasUnknownAverage() {
  for(samples in listOf(emptyList(),listOf(HeartSample(start,100)))) {
   val r=HeartRateZones.recap(session,samples)
   assertNull(r.averageBpm);assertEquals(0.0,r.coveredSeconds,0.0);assertTrue(r.zones.all{it==0.0})
  }
 }
}
