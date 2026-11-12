package com.gwensmuda.euros
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
class WeekAgendaTest {
 private val week=LocalDate.parse("2026-11-09")
 private val zone=ZoneId.of("America/Chicago")
 private fun activity(id:String,time:String,parent:String="",status:String="pending",deleted:Boolean=false)=Record("activities",id,1,JSONObject().put("title",id).put("kind","recovery").put("status",status).put("scheduled_at",time).put("parent_id",parent).toString(),deleted)
 @Test fun matchesExistingActivityAndStepOrderingWithoutReadingFoodOrNutrition() {
  val parent=activity("parent","2026-11-12T18:30:00-06:00")
  val early=activity("early","2026-11-12T08:00:00-06:00",status="skipped")
  val child=activity("child","2026-11-13T00:01:00-06:00",parent.id,"done")
  val records=listOf(parent,child,early,activity("deleted","2026-11-12T12:00:00-06:00",deleted=true),activity("outside","2026-11-16T08:00:00-06:00"),Record("meal_plans","irrelevant",1,"not parsed"),Record("weight_entries","irrelevant",1,"not parsed"))
  val result=WeekAgenda.project(records,week,zone)
  for(day in 0L..6L)assertEquals(PlanDomain.rootActivities(records).filter{PlanDomain.day(it,zone)==week.plusDays(day)},result.onDay(week.plusDays(day)))
  assertEquals(listOf(child),result.childrenOf(parent))
  assertEquals(PlanDomain.progress(parent,records),PlanDomain.progress(parent,result.rowRecords(parent)))
  assertEquals("done",PlanDomain.progress(parent,result.rowRecords(parent)))
 }
 @Test fun groupingRecomputesForZoneMoveAndDeletion() {
  val r=activity("travel","2026-11-09T00:30:00Z")
  assertTrue(WeekAgenda.project(listOf(r),week,zone).days.isEmpty())
  assertEquals(listOf(r),WeekAgenda.project(listOf(r),week,ZoneId.of("Europe/Lisbon")).onDay(week))
  val moved=r.copy(payload=JSONObject(r.payload).put("scheduled_at","2026-11-12T10:00:00-06:00").toString())
  assertEquals(listOf(moved),WeekAgenda.project(listOf(moved),week,zone).onDay(week.plusDays(3)))
  assertTrue(WeekAgenda.project(listOf(moved.copy(deleted=true)),week,zone).days.isEmpty())
 }
}
