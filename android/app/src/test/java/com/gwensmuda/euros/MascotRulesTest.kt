package com.gwensmuda.euros

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MascotRulesTest {
 private val now=Instant.parse("2026-10-14T18:00:00Z")
 private val zone=ZoneId.of("America/Chicago")
 private fun action(id:String,status:String="pending",kind:String="bjj",at:String="2026-10-14T08:00:00-05:00",parent:String="",revision:Long=1)=Record("activities",id,revision,JSONObject().put("kind",kind).put("status",status).put("scheduled_at",at).put("parent_id",parent).toString())
 private fun state(vararg records:Record)=MascotRules.evaluate(records.toList(),now,zone)
 @Test fun unknownAndElapsedPlansStayNormal() {
  assertEquals(MascotDisposition.NORMAL,state().disposition)
  assertEquals(MascotDisposition.NORMAL,state(action("old",at="2026-10-01T08:00:00-05:00"),action("today")).disposition)
 }
 @Test fun twoExplicitSkipsWithinThreeLocalDaysAreJudgyButOneIsNot() {
  val first=action("a","skipped",at="2026-10-12T00:00:00-05:00")
  assertEquals(MascotDisposition.NORMAL,state(first).disposition)
  val result=state(first,action("b","skipped"))
  assertEquals(MascotDisposition.JUDGY,result.disposition);assertEquals(setOf("a","b"),result.qualifyingSkipIds)
 }
 @Test fun windowUsesLocalCalendarDaysNotSeventyTwoHours() {
  val outside=action("old","skipped",at="2026-10-12T04:59:59Z") // Sunday23:59 in Chicago.
  assertEquals(MascotDisposition.NORMAL,state(outside,action("today","skipped")).disposition)
 }
 @Test fun laterCompletionClearsSkipsButEarlierCompletionDoesNot() {
  val skips=listOf(action("a","skipped",at="2026-10-13T08:00:00-05:00"),action("b","skipped",at="2026-10-13T12:00:00-05:00"))
  assertEquals(MascotDisposition.NORMAL,MascotRules.evaluate(skips+action("done","done"),now,zone).disposition)
  assertEquals(MascotDisposition.JUDGY,MascotRules.evaluate(skips+action("done","done",at="2026-10-12T08:00:00-05:00"),now,zone).disposition)
 }
 @Test fun futureScheduledSessionCompletedNowUsesActualCompletionTime() {
  val done=action("done","done",at="2026-10-20T08:00:00-05:00")
  val actual=done.copy(payload=JSONObject(done.payload).put("completed_at","2026-10-14T12:00:00-05:00").toString())
  assertEquals(MascotDisposition.NORMAL,state(action("a","skipped"),action("b","skipped"),actual).disposition)
 }
 @Test fun actualSkipTimeOverridesScheduledDate() {
  val earlier=action("a","skipped",at="2026-10-01T08:00:00-05:00")
  val skippedNow=earlier.copy(payload=JSONObject(earlier.payload).put("skipped_at","2026-10-14T12:00:00-05:00").toString())
  assertEquals(MascotDisposition.JUDGY,state(skippedNow,action("b","skipped")).disposition)
 }
 @Test fun routineChildrenCountOnceAndOnlyWhenAllSkipped() {
  val parent=action("routine",kind="recovery")
  val children=(1..4).map{action("step$it","skipped","recovery",parent="routine")}
  assertEquals(setOf("routine"),MascotRules.evaluate(children+parent,now,zone).qualifyingSkipIds)
  assertEquals(MascotDisposition.JUDGY,MascotRules.evaluate(children+parent+action("b","skipped"),now,zone).disposition)
  assertTrue(MascotRules.evaluate(children.dropLast(1)+action("step4",kind="recovery",parent="routine")+parent,now,zone).qualifyingSkipIds.isEmpty())
 }
 @Test fun completedRoutineClearsAtLastChildCompletionAndIgnoresStaleParentStatus() {
  val parent=action("routine","skipped","recovery")
  val children=listOf(action("one","done","recovery",parent="routine",at="2026-10-14T09:00:00-05:00"),action("two","done","recovery",parent="routine",at="2026-10-14T10:00:00-05:00"))
  assertEquals(MascotDisposition.NORMAL,MascotRules.evaluate(children+parent+action("a","skipped")+action("b","skipped"),now,zone).disposition)
 }
 @Test fun editsAndTombstonesRecomputeWithoutStickyJudgment() {
  val skipped=action("a","skipped")
  assertEquals(MascotDisposition.NORMAL,state(skipped,action("a",revision=2),action("b","skipped")).disposition)
  assertEquals(MascotDisposition.NORMAL,state(skipped,skipped.copy(deleted=true,revision=2),action("b","skipped")).disposition)
 }
 @Test fun foodWeightAndRestCannotCreateOrClearJudgment() {
  val harmless=listOf(action("rest","skipped","rest"),action("food","skipped","food"),Record("weight_entries","weight",1,"{}"))
  assertEquals(MascotDisposition.NORMAL,MascotRules.evaluate(harmless,now,zone).disposition)
  assertEquals(MascotDisposition.JUDGY,MascotRules.evaluate(harmless+action("a","skipped")+action("b","skipped")+action("meal","done","food",at="2026-10-14T12:00:00-05:00"),now,zone).disposition)
 }
 @Test fun judgyOverridesPlannedContextWhileNormalUsesContextWithoutPersistentReaction() {
  val skipped=listOf(action("a","skipped"),action("b","skipped"))
  for((kind,pose) in mapOf("bjj" to MascotPose.TRAINING,"weights" to MascotPose.TRAINING,"recovery" to MascotPose.RECOVERY,"rest" to MascotPose.REST)) {
   val result=MascotRules.evaluate(skipped,now,zone,action("next",kind=kind))
   assertEquals(MascotPose.JUDGY,result.pose);assertEquals(MascotDisposition.JUDGY,result.disposition)
   assertEquals(pose,MascotRules.evaluate(emptyList(),now,zone,action("next",kind=kind)).pose)
  }
  assertEquals(MascotPose.IDLE,state(action("done","done")).pose)
 }
 @Test fun invalidAndFutureTimestampsNeverInventFailures() {
  assertEquals(MascotDisposition.NORMAL,state(action("a","skipped",at="invalid"),action("b","skipped",at="2026-10-15T08:00:00-05:00")).disposition)
 }
 @Test fun trainingParentUsesItsOwnStatusRatherThanRecoveryChildStatus() {
  val parent=action("training",kind="bjj")
  val skippedChild=action("child","skipped","recovery",parent="training")
  assertTrue(state(parent,skippedChild).qualifyingSkipIds.isEmpty())
  val skippedTraining=action("training","skipped","bjj")
  assertEquals(setOf("training"),state(skippedTraining,action("child","done","recovery",parent="training")).qualifyingSkipIds)
 }
}
