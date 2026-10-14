package com.gwensmuda.euros

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit count-only diagnostic. Never launches an activity, grants permissions or saves health data. */
@RunWith(AndroidJUnit4::class)
class HealthConnectProbeTest {
 @Test fun reportExistingAccessAndCoverage() = runBlocking<Unit> {
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val context=instrumentation.targetContext
  val report=JSONObject().put("schema_version",1).put("diagnostic_only",true)
   .put("raw_health_values_included",false).put("permission_request_launched",false)
  try {
   val result=withTimeout(90_000) { HealthConnectProbe(context).inspect(days=7) }
   report.put("available",result.available).put("status",result.status).put("days",result.days)
   val categories=JSONArray()
   result.categories.forEach { category ->
    val sources=JSONArray()
    category.sources.forEach { source -> sources.put(JSONObject()
     .put("source_package",source.sourcePackage).put("record_count",source.recordCount).put("sample_count",source.sampleCount)) }
    categories.put(JSONObject().put("name",category.name).put("granted",category.granted)
     .put("sources",sources).put("truncated",category.truncated).put("error",category.error ?: JSONObject.NULL))
   }
   report.put("categories",categories)
   val coverage=JSONArray()
   result.coverage.forEach { row -> coverage.put(JSONObject()
    .put("source_package",row.sourcePackage).put("session_count",row.sessionCount)
    .put("heart_rate_readable",row.heartRateReadable).put("partial",row.partial)
    .put("sessions_with_samples",if(row.heartRateReadable) row.sessionsWithSamples else JSONObject.NULL)
    .put("covered_seconds",if(row.heartRateReadable) row.coveredSeconds else JSONObject.NULL)
    .put("session_seconds",row.sessionSeconds)) }
   report.put("coverage",coverage)
  } catch(_:Exception) {
   // Exception text may contain source details; only emit a fixed safe diagnostic state.
   report.put("status","probe_failed_or_timed_out")
  }
  instrumentation.sendStatus(0,Bundle().apply { putString("stream","HEALTH_CONNECT_REPORT "+report.toString()+"\n") })
 }
}
