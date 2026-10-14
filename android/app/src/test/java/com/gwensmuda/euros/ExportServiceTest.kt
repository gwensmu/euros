package com.gwensmuda.euros
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ExportServiceTest {
 @Test fun envelopeRejectsUnsupportedUnitsOrVersion() {
  val good=org.json.JSONObject().put("schema_version",1).put("units",ExportService.units()).put("records",org.json.JSONArray())
  assertEquals(0,ExportService.validateEnvelope(good).length())
  assertTrue(runCatching{ExportService.validateEnvelope(org.json.JSONObject(good.toString()).put("schema_version",2))}.isFailure)
  good.getJSONObject("units").put("body_weight","kg")
  assertTrue(runCatching{ExportService.validateEnvelope(good)}.isFailure)
 }

 @Test fun exportPreservesTombstonesAndPendingWithoutSecretsOrHealthByDefault()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("euros.db");val db=EurosDatabase.open(context)
  try {
   db.dao().put(Record("activities","gone",2,"{}",true))
   db.dao().enqueue(Pending("pending","activities","new",0,"{\"status\":\"pending\"}"))
   db.dao().meta(Metadata("server_identity","https://private.invalid"));db.dao().meta(Metadata("token","private"));db.dao().meta(Metadata(HealthImport.KEY,"{\"schema\":1}"))
   val export=ExportService(context,db.dao()).document()
   assertEquals(1,export.getJSONArray("records").length());assertTrue(export.getJSONArray("records").getJSONObject(0).getBoolean("deleted"))
   assertEquals("pending",export.getJSONArray("pending_commands").getJSONObject(0).getString("command_id"))
   assertFalse(export.has("health_imports"));assertFalse(export.toString().contains("private"))
  } finally {db.close();context.deleteDatabase("euros.db")}
 }
 @Test fun healthSnapshotRequiresExplicitSelection()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("euros.db");val db=EurosDatabase.open(context)
  try {db.dao().meta(Metadata(HealthImport.KEY,"{\"schema\":1}"));val export=ExportService(context,db.dao()).document(includeHealth=true);assertTrue(export.getBoolean("health_included"));assertEquals(1,export.getJSONObject("health_imports").getInt("schema"))}
  finally {db.close();context.deleteDatabase("euros.db")}
 }
}
