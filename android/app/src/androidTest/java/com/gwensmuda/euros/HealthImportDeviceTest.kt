package com.gwensmuda.euros

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Explicitly requested on-device import. Reads existing grants only; no health payloads in output. */
@RunWith(AndroidJUnit4::class)
class HealthImportDeviceTest {
 @Test fun importRequestedHistoryAndReportSourceCounts()=runBlocking<Unit> {
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val context=instrumentation.targetContext
  val report=JSONObject().put("schema_version",1).put("raw_health_values_included",false).put("uploaded",false)
  try {
   val dao=(context.applicationContext as EurosApplication).repository.dao
   val importer=HealthImport(context,dao)
   val result=withTimeout(120_000) {importer.refreshFrom(HealthSources.requestedStart())}
   report.put("status",if(result.messages.isEmpty()) "imported" else "partial_access").put("source_counts",JSONObject().apply{importer.sourceCounts().forEach{(source,counts)->put(source,JSONObject(counts))}})
  } catch(_:Exception) {report.put("status","import_failed_or_access_missing")}
  instrumentation.sendStatus(0,Bundle().apply{putString("stream","HEALTH_IMPORT_REPORT "+report.toString()+"\n")})
 }
}
