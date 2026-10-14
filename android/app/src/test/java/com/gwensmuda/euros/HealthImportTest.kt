package com.gwensmuda.euros
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class HealthImportTest {
 private val start=Instant.parse("2026-10-14T00:00:00Z");private val end=start.plusSeconds(86400)
 private fun record(id:String,source:String="polar",from:Instant=start.plusSeconds(100),to:Instant=start.plusSeconds(200))=JSONObject().put("kind","exercise").put("source",source).put("source_id",id).put("start",from.toString()).put("end",to.toString()).put("deleted",false)
 @Test fun correctedSamplesDoNotDuplicateAndUseNewestValue() {
  val values=HealthSnapshots.deduplicateSamples(listOf(HeartSample(start,100),HeartSample(start.plusSeconds(10),110),HeartSample(start,105)))
  assertEquals(2,values.size);assertEquals(105L,values.first().bpm);assertEquals(start,values.first().time)
 }
 @Test fun replayAndCorrectionKeepOneIdentity() {
  val records=JSONObject();val original=record("1")
  repeat(2){HealthSnapshots.reconcile(records,"exercise",start,end,listOf(original))}
  assertEquals(1,records.length())
  val correction=record("1",to=start.plusSeconds(300));HealthSnapshots.reconcile(records,"exercise",start,end,listOf(correction))
  assertEquals(1,records.length());assertEquals(correction.toString(),records.getJSONObject(HealthSnapshots.key(original)).toString())
 }
 @Test fun absentCoveredRecordTombstonedButHistoryAndBoundaryPreserved() {
  val records=JSONObject();val inside=record("inside");val older=record("older",from=start.minusSeconds(1000),to=start.minusSeconds(900));val boundary=record("boundary",from=start.minusSeconds(30))
  listOf(inside,older,boundary).forEach{records.put(HealthSnapshots.key(it),it)}
  HealthSnapshots.reconcile(records,"exercise",start,end,emptyList())
  assertTrue(inside.getBoolean("deleted"));assertFalse(older.getBoolean("deleted"));assertFalse(boundary.getBoolean("deleted"))
 }
 @Test fun providersNeverCollapseIntoOneSourceIdentity() {
  val records=JSONObject();HealthSnapshots.reconcile(records,"exercise",start,end,listOf(record("1"),record("1","fitbit")))
  assertEquals(2,records.length())
 }
 @Test fun categoryRefreshDoesNotDeleteAnotherCategory() {
  val records=JSONObject();val weight=record("weight").put("kind","weight");records.put(HealthSnapshots.key(weight),weight)
  HealthSnapshots.reconcile(records,"exercise",start,end,emptyList());assertFalse(weight.getBoolean("deleted"))
 }
 @Test fun explicitLinkIsUniqueAndCanChangeWithoutStealing() {
  val links=JSONObject();HealthSnapshots.link(links,"a","polar:1")
  assertTrue(runCatching{HealthSnapshots.link(links,"b","polar:1")}.isFailure)
  assertEquals("polar:1",links.getString("a"));assertFalse(links.has("b"))
  HealthSnapshots.link(links,"a","polar:2");HealthSnapshots.link(links,"b","polar:1");assertEquals(2,links.length())
 }
 @Test fun candidateWindowDoesNotCountAsCompletion() {
  assertTrue(HealthSnapshots.isCandidate(start,start.plusSeconds(3600),start.plusSeconds(7200)))
  assertFalse(HealthSnapshots.isCandidate(start,start.plusSeconds(14400),start.plusSeconds(18000)))
  assertFalse(HealthSnapshots.isCandidate(start,start,start))
 }
}
