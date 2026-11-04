package com.gwensmuda.euros

import androidx.health.connect.client.records.ExerciseSessionRecord
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionLinkingTest {
 private fun activity(id:String="class",at:String="2026-11-03T18:30:00-06:00",status:String="done",kind:String="bjj")=Record("activities",id,1,JSONObject().put("kind",kind).put("status",status).put("scheduled_at",at).toString())
 private fun session(at:String="2026-11-04T00:40:00Z",type:Int=ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS)=JSONObject().put("kind","exercise").put("source",HealthSources.POLAR).put("exercise_type",type).put("start",at).put("end","2026-11-04T02:00:00Z")
 private fun state()=JSONObject().put("records",JSONObject().put("polar",session())).put("links",JSONObject())
 @Test fun uniqueNearbyMatchUsesInstantsAndLeavesActivityUnchanged() {
  val a=activity();val before=a.payload
  assertEquals(mapOf("class" to "polar"),SessionLinking.proposals(listOf(a),state()))
  assertEquals(before,a.payload)
  assertEquals(mapOf("class" to "polar"),SessionLinking.proposals(listOf(activity(at="2026-11-04T00:30:00Z")),state()))
 }
 @Test fun ambiguityInEitherDirectionRequiresChoice() {
  assertTrue(SessionLinking.proposals(listOf(activity(),activity("second")),state()).isEmpty())
  val s=state();s.getJSONObject("records").put("second",session())
  assertTrue(SessionLinking.proposals(listOf(activity()),s).isEmpty())
 }
 @Test fun respectsExplicitLinksUnlinksSkipsAndDeletedRecords() {
  val s=state();SessionLinking.exclude(s,"class","polar")
  assertTrue(SessionLinking.proposals(listOf(activity()),s).isEmpty())
  assertTrue(SessionLinking.proposals(listOf(activity(status="skipped")),state()).isEmpty())
  assertTrue(SessionLinking.proposals(listOf(activity().copy(deleted=true)),state()).isEmpty())
  val linked=state();linked.getJSONObject("links").put("other","polar")
  assertTrue(SessionLinking.proposals(listOf(activity()),linked).isEmpty())
  val deleted=state();deleted.getJSONObject("records").getJSONObject("polar").put("deleted",true)
  assertTrue(SessionLinking.proposals(listOf(activity()),deleted).isEmpty())
 }
 @Test fun doesNotMatchWrongSportDistantTimesOrFitbitMovement() {
  assertTrue(SessionLinking.proposals(listOf(activity(kind="weights")),state()).isEmpty())
  assertTrue(SessionLinking.proposals(listOf(activity(at="2026-11-03T12:00:00-06:00")),state()).isEmpty())
  val fitbit=state();fitbit.getJSONObject("records").getJSONObject("polar").put("source",HealthSources.FITBIT)
  assertTrue(SessionLinking.proposals(listOf(activity()),fitbit).isEmpty())
 }
 @Test fun supportsStrengthWithoutInventingBjj() {
  val s=state();s.getJSONObject("records").put("polar",session(type=ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING))
  assertEquals(mapOf("class" to "polar"),SessionLinking.proposals(listOf(activity(kind="weights")),s))
 }
}
