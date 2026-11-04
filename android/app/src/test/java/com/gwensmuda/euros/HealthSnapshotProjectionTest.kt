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
}
