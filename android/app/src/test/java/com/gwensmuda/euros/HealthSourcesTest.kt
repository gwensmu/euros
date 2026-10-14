package com.gwensmuda.euros
import androidx.health.connect.client.records.ExerciseSessionRecord
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
class HealthSourcesTest {
 private fun exercise(source:String,type:Int=ExerciseSessionRecord.EXERCISE_TYPE_WALKING)=JSONObject().put("kind","exercise").put("source",source).put("exercise_type",type).put("deleted",false)
 @Test fun compressedSnapshotRoundTripsAndReadsLegacy() {
  val snapshot=JSONObject().put("schema",1).put("records",JSONObject().put("record",JSONObject().put("source",HealthSources.POLAR)))
  assertEquals(snapshot.toString(),HealthSnapshotStorage.decode(HealthSnapshotStorage.encode(snapshot)).toString())
  assertEquals(snapshot.toString(),HealthSnapshotStorage.decode(snapshot.toString()).toString())
 }
 @Test fun polarProfileWeightIsNotAnObservedMeasurement() {
  val polar=JSONObject().put("kind","weight").put("source",HealthSources.POLAR).put("deleted",false)
  assertFalse(HealthSources.isObservedWeight(polar))
  val fitbit=JSONObject(polar.toString()).put("source",HealthSources.FITBIT)
  assertTrue(HealthSources.isObservedWeight(fitbit));assertFalse(HealthSources.isObservedWeight(fitbit.put("deleted",true)))
 }
 @Test fun requestedLastWeekStartsAtChicagoMidnight() {assertEquals(Instant.parse("2026-10-05T05:00:00Z"),HealthSources.requestedStart())}
 @Test fun fitbitMovementIsNotTrainingCandidate() {val walk=exercise(HealthSources.FITBIT);assertTrue(HealthSources.isWalk(walk));assertFalse(HealthSources.isTraining(walk));assertTrue(HealthSources.isTraining(exercise(HealthSources.POLAR)))}
 @Test fun unknownSourceAndDeletedSessionsNotUsed() {assertFalse(HealthSources.isTraining(exercise("unknown")));assertFalse(HealthSources.isWalk(exercise("unknown")));assertFalse(HealthSources.isTraining(exercise(HealthSources.POLAR).put("deleted",true)))}
 @Test fun onlyActualFitbitWalkingCountsAsWalking() {assertFalse(HealthSources.isWalk(exercise(HealthSources.FITBIT,ExerciseSessionRecord.EXERCISE_TYPE_RUNNING)));assertFalse(HealthSources.isWalk(exercise(HealthSources.POLAR)))}
 @Test fun overlappingWalkingIntervalsCountOnce() {val start=Instant.parse("2026-10-05T05:00:00Z");assertEquals(90L,HealthSources.unionSeconds(listOf(start to start.plusSeconds(60),start.plusSeconds(30) to start.plusSeconds(90))))}
 @Test fun separateWalkingIntervalsAddButReversedDoNot() {val start=Instant.parse("2026-10-05T05:00:00Z");assertEquals(120L,HealthSources.unionSeconds(listOf(start to start.plusSeconds(60),start.plusSeconds(120) to start.plusSeconds(180),start.plusSeconds(200) to start)))}
}
