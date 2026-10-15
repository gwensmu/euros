package com.gwensmuda.euros

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class RichReminderRulesTest {
 private val date=LocalDate.parse("2026-10-15")
 private val zone=PlanDomain.zone
 private fun activity(id:String="class",kind:String="bjj",status:String="pending",day:LocalDate=date,extra:(JSONObject)->Unit={}):Record {
  val p=JSONObject().put("kind",kind).put("title",if(kind=="bjj")"Comp class" else "Recovery").put("status",status).put("scheduled_at","${day}T18:30:00-05:00");extra(p)
  return Record("activities",id,1,p.toString())
 }
 private fun plans(records:List<Record> = listOf(activity()),day:LocalDate=date,settings:ReminderSettings=ReminderSettings(true),ends:Map<String,Instant> = emptyMap(),dismissed:Set<String> = emptySet())=RichReminderRules.plans(records,settings,day,zone,listOf(FoodMealReference("breakfast","Breakfast",null,null,null,null,foods=listOf("eggs","toast"))),dismissed,ends)
 @Test fun trainingIsAnHourBeforeAndUnknownNeverMeansSkipped(){val p=plans().single{it.kind=="training"};assertEquals(Instant.parse("2026-10-15T22:30:00Z"),p.due);assertEquals("Comp class is scheduled for Oct 15 at 6:30 PM.",p.facts);assertFalse(p.allowSkipJoke)}
 @Test fun explicitYesterdaySkipMayBeMentionedButLaterCompletionSuppressesIt(){val yesterday=activity("old",status="skipped",day=date.minusDays(1));assertTrue(plans(listOf(yesterday,activity())).single{it.kind=="training"}.allowSkipJoke);assertFalse(plans(listOf(yesterday,activity(),activity("done",status="done"))).single{it.kind=="training"}.allowSkipJoke)}
 @Test fun morningHasActualFoodsAndRestSteps(){val rest=activity(kind="rest");val child=activity("step",kind="recovery"){it.put("parent_id",rest.id).put("title","Breathing")};val p=plans(listOf(rest,child)).single{it.kind=="morning"};assertTrue(p.facts.contains("eggs, toast"));assertTrue(p.facts.contains("Breathing"));assertEquals(0,plans(listOf(rest,child)).count{it.kind=="training"})}
 @Test fun sundayReplacesMorningAndWeightChangesCopy(){val sunday=LocalDate.parse("2026-10-18");val before=plans(emptyList(),day=sunday);assertEquals(0,before.count{it.kind=="morning"});assertTrue(before.single{it.kind=="sunday"}.facts.contains("weigh-in"));val weight=Record("weight_entries","w",1,JSONObject().put("local_date",sunday.toString()).toString());assertTrue(plans(listOf(weight),day=sunday).single{it.kind=="sunday"}.facts.startsWith("Your weight is logged"))}
 @Test fun dismissalRemovesMealWithoutClaimingIntakeAndChangesCacheKey(){val before=plans().single{it.kind=="food"};val after=plans(dismissed=setOf("breakfast")).single{it.kind=="food"};assertTrue(before.facts.contains("Breakfast"));assertFalse(after.facts.contains("Breakfast"));assertNotEquals(before.contextKey,after.contextKey);assertFalse(after.facts.contains("eaten"))}
 @Test fun recoveryRequiresKnownEndAndTrackerWins(){val session=activity();val recovery=activity("recovery",kind="recovery"){it.put("linked_session_id","class")};assertFalse(plans(listOf(session,recovery)).any{it.kind=="recovery"});val end=Instant.parse("2026-10-16T01:00:00Z");assertEquals(end.plusSeconds(900),plans(listOf(session,recovery),ends=mapOf("class" to end)).single{it.kind=="recovery"}.due);val withDuration=activity{it.put("prescription",JSONObject().put("durationMinutes",90))};assertEquals(Instant.parse("2026-10-16T01:15:00Z"),plans(listOf(withDuration,recovery)).single{it.kind=="recovery"}.due)}
 @Test fun completionCancelsTrainingAndRecoveryChildrenCancelRoutine(){assertFalse(plans(listOf(activity(status="done"))).any{it.kind=="training"});val r=activity("recovery",kind="recovery"){it.put("linked_session_id","class")};val child=activity("child",kind="recovery",status="skipped"){it.put("parent_id",r.id)};assertFalse(plans(listOf(activity(),r,child),ends=mapOf("class" to Instant.parse("2026-10-16T01:00:00Z"))).any{it.kind=="recovery"})}
 @Test fun quietRemindersAreNotPiledUpAtQuietEnd(){val p=plans(settings=ReminderSettings(true,"17:00","09:00"));assertFalse(p.any{it.kind=="training" || it.kind=="food"});assertEquals(Instant.parse("2026-10-15T14:00:00Z"),p.single().due)}
 @Test fun disabledMeansNoReminders(){assertTrue(plans(settings=ReminderSettings(false)).isEmpty())}
 @Test fun movingChangesCacheButNotIdentity(){val before=plans().single{it.kind=="training"};val moved=activity{it.put("scheduled_at","2026-10-15T19:30:00-05:00")};val after=plans(listOf(moved)).single{it.kind=="training"};assertEquals(before.id,after.id);assertNotEquals(before.contextKey,after.contextKey)}
 @Test fun trainingFactsRemainUnambiguousWhenSnoozedAcrossMidnight(){
  val skipped=activity("old",status="skipped",day=date.minusDays(1))
  val p=plans(listOf(skipped,activity())).single{it.kind=="training"}
  assertEquals("Comp class is scheduled for Oct 15 at 6:30 PM. You marked the Oct 14 class skipped.",p.facts)
  assertFalse(p.facts.contains("yesterday"));assertFalse(p.facts.contains("tonight"))
 }

}
