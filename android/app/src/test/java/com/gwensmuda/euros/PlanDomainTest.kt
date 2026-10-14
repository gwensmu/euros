package com.gwensmuda.euros
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.time.*
class PlanDomainTest {
 private fun item(id:String,status:String="pending",parent:String="",at:String="2026-10-14T08:00:00-05:00")=Record("activities",id,1,JSONObject().put("scheduled_at",at).put("status",status).put("parent_id",parent).toString())
 @Test fun earlierPendingRemainsActionable(){val list=listOf(item("early"),item("late",at="2026-10-14T20:00:00-05:00"));assertEquals("early",PlanDomain.next(list,LocalDate.parse("2026-10-14"))?.id)}
 @Test fun recoveryCannotCompleteFromOneChild(){val parent=item("routine");assertEquals("partial",PlanDomain.progress(parent,listOf(parent,item("a","done","routine"),item("b",parent="routine"))))}
 @Test fun allSkippedIsNotCompleted(){val parent=item("routine");assertEquals("skipped",PlanDomain.progress(parent,listOf(item("a","skipped","routine"))))}
 @Test fun mixedTerminalRecoveryIsPartial(){val parent=item("routine");assertEquals("partial",PlanDomain.progress(parent,listOf(item("a","done","routine"),item("b","skipped","routine"))))}
 @Test fun mixedTerminalDoesNotRemainNext(){val p=item("routine");val all=listOf(p,item("a","done","routine"),item("b","skipped","routine"));assertNull(PlanDomain.next(all,LocalDate.parse("2026-10-14")))}
 @Test fun childrenDoNotDuplicateTimeline(){assertEquals(1,PlanDomain.rootActivities(listOf(item("r"),item("c",parent="r"))).size)}
 @Test fun chicagoDateHandlesOffset(){assertEquals(LocalDate.parse("2026-10-14"),PlanDomain.day(item("a",at="2026-10-15T01:00:00Z")))}
 @Test fun dstOffsetChanges(){assertTrue(PlanDomain.moveTimestamp("2026-10-31","11:00").endsWith("-05:00"));assertTrue(PlanDomain.moveTimestamp("2026-11-02","11:00").endsWith("-06:00"))}
 @Test fun ambiguousHourRejected(){assertThrows(IllegalArgumentException::class.java){PlanDomain.moveTimestamp("2026-11-01","01:30")}}
 @Test fun missingHourRejected(){assertThrows(IllegalArgumentException::class.java){PlanDomain.moveTimestamp("2027-03-14","02:30")}}
 @Test fun weightRejectsInvalidAndPreservesDecimal(){assertEquals("145.25",PlanDomain.weightPayload("145.25",OffsetDateTime.parse("2026-10-18T09:00:00-05:00")).getString("weight_lb"));listOf("0","-1","NaN","1501").forEach{v->assertThrows(IllegalArgumentException::class.java){PlanDomain.weightPayload(v,OffsetDateTime.now())}}}
 @Test fun authoredWednesdayRemainsOneActionableParentUntilEachRecoveryStepIsResolved() {
  val commands=org.json.JSONArray(java.io.File("src/main/assets/schedule-commands.json").readText())
  val records=(0 until commands.length()).map{commands.getJSONObject(it)}.map{Record("activities",it.getString("entity_id"),0,it.getJSONObject("payload").toString())}
  val day=LocalDate.parse("2026-10-14")
  val parent=PlanDomain.onDay(records,day).single()
  assertEquals("capacity-2026-10-14-rest-wed",parent.id)
  assertEquals(4,PlanDomain.children(parent,records).size)
  val parentDone=parent.copy(payload=PlanDomain.payload(parent).put("status","done").toString())
  val completedParent=records.map{if(it.id==parent.id)parentDone else it}
  assertEquals("pending",PlanDomain.progress(parentDone,completedParent))
  assertEquals(parent.id,PlanDomain.next(completedParent,day)?.id)
  val done=completedParent.map{if(PlanDomain.payload(it).optString("parent_id")==parent.id)it.copy(payload=PlanDomain.payload(it).put("status","done").toString()) else it}
  assertEquals("done",PlanDomain.progress(parentDone,done));assertNull(PlanDomain.next(done,day))
 }
}
