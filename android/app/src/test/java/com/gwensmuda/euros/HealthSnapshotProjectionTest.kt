package com.gwensmuda.euros

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HealthSnapshotProjectionTest {
 @Test fun projectionsSkipLargeSampleArraysWithoutChangingStoredHistory() {
  val samples=JSONArray().apply{repeat(40000){put(JSONObject().put("time","2026-10-30T12:00:00Z").put("bpm",70))}}
  // Samples precede source; projection must not depend on JSON field order.
  val records=JSONObject().put("fitbit",JSONObject().put("samples",samples).put("kind","heart_rate").put("source",HealthSources.FITBIT))
   .put("polar",JSONObject().put("samples",JSONArray().put(JSONObject().put("bpm",140))).put("source",HealthSources.POLAR))
  val original=JSONObject().put("records",records).put("links",JSONObject().put("activity","exercise")).put("refreshed_at","2026-10-30T12:00:00Z")
  val raw=HealthSnapshotStorage.encode(original)
  val lightweight=HealthSnapshotStorage.decode(raw,HealthSnapshotStorage.Samples.NONE)
  assertFalse(lightweight.getJSONObject("records").getJSONObject("fitbit").has("samples"))
  assertEquals("exercise",lightweight.getJSONObject("links").getString("activity"))
  val polar=HealthSnapshotStorage.decode(raw,HealthSnapshotStorage.Samples.POLAR).getJSONObject("records")
  assertFalse(polar.getJSONObject("fitbit").has("samples"))
  assertEquals(140,polar.getJSONObject("polar").getJSONArray("samples").getJSONObject(0).getInt("bpm"))
  assertEquals(40000,HealthSnapshotStorage.decode(raw).getJSONObject("records").getJSONObject("fitbit").getJSONArray("samples").length())
 }
 @Test fun legacyAndCompressedPreserveNullsNumbersAndEscapedText() {
  val value=JSONObject().put("null",JSONObject.NULL).put("decimal",12.5).put("text","a\n\"b").put("records",JSONObject())
  for(raw in listOf(value.toString(),HealthSnapshotStorage.encode(value))) {
   val result=HealthSnapshotStorage.decode(raw)
   assertTrue(result.has("null"));assertTrue(result.isNull("null"))
   assertEquals(12.5,result.getDouble("decimal"),0.0);assertEquals("a\n\"b",result.getString("text"))
  }
 }
 @Test fun mutationProjectionPreservesOpaqueSamplesThroughLinkAndRecordChanges() {
  val sample=JSONObject().put("time","2026-10-30T12:00:00Z").put("bpm",70).put("annotation","a\n\"b").put("missing",JSONObject.NULL).put("precision",java.math.BigDecimal("0.123456789123456789"))
  val original=JSONObject().put("schema",1).put("records",JSONObject()
   .put("old",JSONObject().put("samples",JSONArray().apply{repeat(40000){put(sample)}}).put("kind","heart_rate").put("source",HealthSources.FITBIT)))
   .put("links",JSONObject()).put("auto_link_exclusions",JSONObject().put("activity",JSONObject().put("other",true)))
  for(raw in listOf(original.toString(),HealthSnapshotStorage.encode(original))) {
   val mutable=HealthSnapshotStorage.decode(raw,HealthSnapshotStorage.Samples.PACKED)
   assertTrue(mutable.getJSONObject("records").getJSONObject("old").get("samples") is HealthSnapshotStorage.PackedSamples)
   mutable.getJSONObject("links").put("activity","workout")
   mutable.getJSONObject("records").put("new",JSONObject().put("samples",HealthSnapshotStorage.packSamples{w->w.beginObject().name("bpm").value(140).endObject()}))
   val restored=HealthSnapshotStorage.decode(HealthSnapshotStorage.encode(mutable))
   assertEquals(original.getJSONObject("records").getJSONObject("old").toString(),restored.getJSONObject("records").getJSONObject("old").toString())
   assertEquals(140,restored.getJSONObject("records").getJSONObject("new").getJSONArray("samples").getJSONObject(0).getInt("bpm"))
   assertEquals("workout",restored.getJSONObject("links").getString("activity"))
   assertEquals(original.getJSONObject("auto_link_exclusions").toString(),restored.getJSONObject("auto_link_exclusions").toString())
  }
 }
 @Test fun filteredHeartRateReconciliationCannotDeleteAnotherSourcesHistory() {
  fun hr(source:String,id:String)=JSONObject().put("kind","heart_rate").put("source",source).put("source_id",id).put("start","2026-10-30T12:00:00Z").put("end","2026-10-30T13:00:00Z").put("samples",JSONArray()).put("deleted",false)
  val fitbit=hr(HealthSources.FITBIT,"f");val polar=hr(HealthSources.POLAR,"p")
  val records=JSONObject().put(HealthSnapshots.key(fitbit),fitbit).put(HealthSnapshots.key(polar),polar)
  HealthSnapshots.reconcile(records,"heart_rate",java.time.Instant.parse("2026-10-30T00:00:00Z"),java.time.Instant.parse("2026-10-31T00:00:00Z"),emptyList(),HealthSources.POLAR)
  assertFalse(fitbit.getBoolean("deleted"));assertTrue(polar.getBoolean("deleted"))
 }

}
