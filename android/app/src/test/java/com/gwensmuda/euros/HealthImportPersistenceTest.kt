package com.gwensmuda.euros
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class HealthImportPersistenceTest {
 @Test fun polarObservedAndFitbitMovementStaySeparateAndExplicitLinksAreDiscoverable()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("euros.db");val db=EurosDatabase.open(context)
  try {
   fun imported(source:String,id:String)=JSONObject().put("kind","exercise").put("source",source).put("source_id",id).put("start","2026-10-14T18:00:00Z").put("end","2026-10-14T19:00:00Z").put("exercise_type",androidx.health.connect.client.records.ExerciseSessionRecord.EXERCISE_TYPE_WALKING).put("deleted",false)
   val polar=imported(HealthSources.POLAR,"p");val fitbit=imported(HealthSources.FITBIT,"f");val records=JSONObject().put(HealthSnapshots.key(polar),polar).put(HealthSnapshots.key(fitbit),fitbit)
   db.dao().meta(Metadata(HealthImport.KEY,HealthSnapshotStorage.encode(JSONObject().put("schema",1).put("records",records).put("links",JSONObject()))))
   val activity=Record("activities","class",1,"{\"kind\":\"bjj\",\"scheduled_at\":\"2026-10-14T13:00:00-05:00\",\"status\":\"pending\"}");db.dao().put(activity)
   val importer=HealthImport(context,db.dao());val date=java.time.LocalDate.of(2026,10,14)
   assertEquals(HealthSources.POLAR,importer.sessions(date,date).single().sourcePackage)
   assertEquals(1,importer.candidates(activity).size);assertEquals(60L,importer.movement(date).workoutWalkMinutes)
   assertTrue(runCatching{importer.link(activity.id,HealthSnapshots.key(fitbit))}.isFailure)
   importer.link(activity.id,HealthSnapshots.key(polar));assertEquals(HealthSnapshots.key(polar),importer.linkedActivities()[activity.id])
   assertEquals("pending",JSONObject(db.dao().record("activities",activity.id)!!.payload).getString("status"))
   assertTrue(db.dao().pending().isEmpty())
  } finally {db.close();context.deleteDatabase("euros.db")}
 }
 @Test fun weightCandidatesExcludePolarProfileAndPreserveItsRawRecord()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("euros.db");val db=EurosDatabase.open(context)
  try {
   fun weight(source:String)=JSONObject().put("kind","weight").put("source",source).put("source_id",source).put("start","2026-10-14T18:00:00Z").put("end","2026-10-14T18:00:00Z").put("weight_lb",160).put("deleted",false)
   val polar=weight(HealthSources.POLAR);val fitbit=weight(HealthSources.FITBIT);val records=JSONObject().put(HealthSnapshots.key(polar),polar).put(HealthSnapshots.key(fitbit),fitbit)
   db.dao().meta(Metadata(HealthImport.KEY,HealthSnapshotStorage.encode(JSONObject().put("schema",1).put("records",records).put("links",JSONObject()))))
   val importer=HealthImport(context,db.dao());assertEquals(HealthSources.FITBIT,importer.weightCandidates().single().sourcePackage)
   assertEquals(1,importer.sourceCounts()[HealthSources.POLAR]?.get("weight"))
  } finally {db.close();context.deleteDatabase("euros.db")}
 }
 @Test fun removingImportsPreservesManualRecordsAndPendingWrites()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("euros.db");val db=EurosDatabase.open(context)
  try {
   val r=Record("weight_entries","manual",1,"{\"weight_lb\":\"160\"}");db.dao().put(r)
   db.dao().enqueue(Pending("queued","activities","new",0,"{}"));db.dao().meta(Metadata(HealthImport.KEY,"{\"schema\":1,\"records\":{},\"links\":{}}"))
   HealthImport(context,db.dao()).clearImports()
   assertNull(db.dao().meta(HealthImport.KEY));assertEquals(r,db.dao().record(r.type,r.id));assertEquals("queued",db.dao().pending().single().commandId)
  } finally {db.close();context.deleteDatabase("euros.db")}
 }
 @Test fun explicitlyLinkedTombstoneSurvivesAsMissingRecap()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("euros.db");val db=EurosDatabase.open(context)
  try {
   val record=JSONObject().put("kind","exercise").put("source",HealthSources.POLAR).put("source_id","1").put("start","2026-10-14T18:00:00Z").put("end","2026-10-14T19:00:00Z").put("exercise_type",1).put("deleted",true)
   val key=HealthSnapshots.key(record);val snapshot=JSONObject().put("schema",1).put("records",JSONObject().put(key,record)).put("links",JSONObject().put("class",key))
   db.dao().meta(Metadata(HealthImport.KEY,snapshot.toString()));val recap=HealthImport(context,db.dao()).recap("class")!!
   assertTrue(recap.session.deleted);assertTrue(recap.samples.isEmpty());assertNull(recap.averageBpm)
   HealthImport(context,db.dao()).unlink("class");assertNull(HealthImport(context,db.dao()).recap("class"))
  } finally {db.close();context.deleteDatabase("euros.db")}
 }
}
