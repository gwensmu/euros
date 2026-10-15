package com.gwensmuda.euros

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class WeekTrackerCacheTest {
 private fun snapshot(bpm:Int=160,linked:Boolean=false,deleted:Boolean=false):String {
  val start=Instant.parse("2026-10-13T23:00:00Z")
  val exercise=JSONObject().put("kind","exercise").put("source",HealthSources.POLAR).put("start",start.toString()).put("end",start.plusSeconds(600).toString()).put("exercise_type",44).put("deleted",deleted)
  val samples=JSONArray().apply{for(i in 0..10)put(JSONObject().put("time",start.plusSeconds(i*60L).toString()).put("bpm",bpm))}
  val hr=JSONObject().put("kind","heart_rate").put("source",HealthSources.POLAR).put("samples",samples)
  return HealthSnapshotStorage.encode(JSONObject().put("records",JSONObject().put("workout",exercise).put("hr",hr)).put("links",JSONObject().apply{if(linked)put("activity","workout")}))
 }
 @Test fun projectionUsesSavedHrAndHonorsCorrectionsLinksAndDeletion() {
  assertEquals("BJJ · Intense",WeekTrackerCache.project(snapshot()).single().label)
  assertEquals("BJJ · Light",WeekTrackerCache.project(snapshot(bpm=100)).single().label)
  assertTrue(WeekTrackerCache.project(snapshot(linked=true)).isEmpty())
  assertTrue(WeekTrackerCache.project(snapshot(deleted=true)).isEmpty())
  assertTrue(WeekTrackerCache.project(null).isEmpty())
 }
 @Test fun tabCollectorsReuseWarmStateAndObserveLocalUpdates()=runBlocking {
  val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
  try {
   val reads=AtomicInteger()
   val source=MutableStateFlow<String?>(snapshot())
   val cache=WeekTrackerCache(source.onStart{reads.incrementAndGet()},scope)
   val loaded=withTimeout(5000){cache.state.first{it.isNotEmpty()}}
   // Opening/closing consumers doesn't restart Room collection or clear the projection.
   repeat(3){assertSame(loaded,cache.state.first());assertSame(loaded,cache.state.value)}
   assertEquals(1,reads.get())
   source.value=snapshot(bpm=100)
   withTimeout(5000){cache.state.first{it.firstOrNull()?.label=="BJJ · Light"}}
   source.value=snapshot(linked=true)
   withTimeout(5000){cache.state.first{it.isEmpty()}}
   assertEquals(1,reads.get())
  }finally{scope.cancel()}
 }
}
