package com.gwensmuda.euros

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class)
class TrackerPublisherTest {
 private val start="2026-10-14T10:00:00Z"
 private fun raw(kind:String,id:String,provider:String=HealthSources.POLAR)=JSONObject().put("kind",kind).put("source",provider).put("source_id",id).put("modified_at",start).put("start",start).put("end","2026-10-14T11:00:00Z").put("deleted",false)
 private fun state():JSONObject {
  val exercise=raw("exercise","session").put("exercise_type",1).put("title","Source workout")
  val heart=raw("heart_rate","hr").put("samples",JSONArray().put(JSONObject().put("time","2026-10-14T09:59:59Z").put("bpm",70)).put(JSONObject().put("time",start).put("bpm",120)).put(JSONObject().put("time","2026-10-14T11:00:01Z").put("bpm",80)))
  val fitbit=raw("exercise","walk",HealthSources.FITBIT).put("exercise_type",79)
  val fitbitHr=raw("heart_rate","fitbit-hr",HealthSources.FITBIT).put("samples",JSONArray().put(JSONObject().put("time",start).put("bpm",90)))
  return JSONObject().put("refreshed_at","2026-10-14T12:00:00Z").put("records",JSONObject().apply{listOf(exercise,heart,fitbit,fitbitHr).forEach{put(HealthSnapshots.key(it),it)}}).put("links",JSONObject())
 }
 private class Server:SyncApi {
  var fail=false
  val calls=mutableListOf<JSONObject>()
  private val receipts=mutableMapOf<String,JSONObject>()
  override fun request(path:String,body:JSONObject?):JSONObject {
   assertEquals("/v1/tracker/import",path);val request=JSONObject(body!!.toString());calls.add(request)
   if(fail)throw java.io.IOException("offline")
   return receipts.getOrPut(request.getString("command_id")) {
    JSONObject().put("command_id",request.getString("command_id")).put("records",JSONArray().apply {
     val records=request.getJSONArray("records")
     for(i in 0 until records.length()) {val record=records.getJSONObject(i);put(JSONObject().put("source_key",record.getString("source_key")).put("revision",record.getLong("expected_revision")+1).put("deleted",record.getBoolean("deleted")))}
    })
   }
  }
 }
 @Test fun projectionExcludesOtherProvidersAndOutOfWindowSamples() {
  val projected=PolarProjection.workouts(state()).single()
  assertEquals("session",projected.getString("source_id"));assertEquals("Source workout",projected.getString("source_title"))
  val samples=projected.getJSONArray("heart_rate_samples");assertEquals(1,samples.length())
  assertEquals(120,samples.getJSONObject(0).getInt("bpm"));assertEquals("hr",samples.getJSONObject(0).getString("source_id"))
  assertEquals(HealthSources.POLAR,samples.getJSONObject(0).getString("source_package"))
 }
 @Test fun successfulPublicationIsIdempotentAndManualRecordsUntouched()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  try {
   val dao=db.dao();val snapshot=state();dao.meta(Metadata(HealthImport.KEY,HealthSnapshotStorage.encode(snapshot)))
   val manual=Record("activities","manual",4,"{\"kind\":\"bjj\",\"status\":\"pending\"}")
   dao.put(manual);val server=Server();val publisher=TrackerPublisher(dao)
   publisher.publish(server);publisher.publish(server)
   assertEquals(1,server.calls.size);assertNull(dao.meta(TrackerPublisher.PENDING))
   assertEquals(manual,dao.record("activities","manual"));assertTrue(dao.pending().isEmpty())
   dao.removeMeta(HealthImport.KEY);publisher.publish(server);assertEquals(1,server.calls.size)
  }finally{db.close()}
 }
 @Test fun failedUploadKeepsExactEnvelopeAndCheckpointAdvancesOnlyAfterAck()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  try {
   val dao=db.dao();dao.meta(Metadata(HealthImport.KEY,HealthSnapshotStorage.encode(state())))
   val server=Server().apply{fail=true};val publisher=TrackerPublisher(dao)
   assertTrue(runCatching{publisher.publish(server)}.isFailure)
   assertNull(dao.meta(TrackerPublisher.CHECKPOINT));assertNotNull(dao.meta(TrackerPublisher.PENDING))
   server.fail=false;TrackerPublisher(dao).publish(server)
   assertEquals(server.calls[0].toString(),server.calls[1].toString());assertNotNull(dao.meta(TrackerPublisher.CHECKPOINT));assertNull(dao.meta(TrackerPublisher.PENDING))
  }finally{db.close()}
 }
 @Test fun correctionLinkUnlinkAndExplicitDeletionProduceRevisionedSnapshots()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  try {
   val dao=db.dao();val local=state();val server=Server();val publisher=TrackerPublisher(dao)
   dao.put(Record("activities","manual",1,"{}"))
   suspend fun publish(){dao.meta(Metadata(HealthImport.KEY,HealthSnapshotStorage.encode(local)));publisher.publish(server)}
   publish()
   val key="exercise:${HealthSources.POLAR}:session"
   local.getJSONObject("links").put("manual",key);publish();publish()
   assertEquals(2,server.calls.size);assertEquals("manual",server.calls.last().getJSONArray("records").getJSONObject(0).getString("activity_id"))
   local.getJSONObject("links").remove("manual");publish()
   assertTrue(server.calls.last().getJSONArray("records").getJSONObject(0).isNull("activity_id"))
   val hr=local.getJSONObject("records").getJSONObject("heart_rate:${HealthSources.POLAR}:hr")
   hr.getJSONArray("samples").getJSONObject(1).put("bpm",150);publish()
   assertEquals(150,server.calls.last().getJSONArray("records").getJSONObject(0).getJSONArray("heart_rate_samples").getJSONObject(0).getInt("bpm"))
   local.getJSONObject("records").getJSONObject(key).put("deleted",true);publish()
   val tombstone=server.calls.last().getJSONArray("records").getJSONObject(0)
   assertTrue(tombstone.getBoolean("deleted"));assertEquals(0,tombstone.getJSONArray("heart_rate_samples").length());assertEquals(4,tombstone.getInt("expected_revision"))
  }finally{db.close()}
 }
 @Test fun sourceHeartRateDeletionRemovesSamplesWithoutRemovingWorkout() {
  val local=state();local.getJSONObject("records").getJSONObject("heart_rate:${HealthSources.POLAR}:hr").put("deleted",true)
  val workout=PolarProjection.workouts(local).single()
  assertFalse(workout.getBoolean("deleted"));assertEquals(0,workout.getJSONArray("heart_rate_samples").length())
 }
 @Test fun unpublishedTombstonesAndDeletedActivityLinksDoNotPoisonUpload()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  try {
   val dao=db.dao();val local=state();val key="exercise:${HealthSources.POLAR}:session"
   val removed=raw("exercise","never-published").put("exercise_type",1).put("deleted",true)
   local.getJSONObject("records").put(HealthSnapshots.key(removed),removed)
   local.getJSONObject("links").put("removed-activity",key)
   dao.put(Record("activities","removed-activity",1,"{}",true))
   dao.meta(Metadata(HealthImport.KEY,HealthSnapshotStorage.encode(local)))
   val server=Server();TrackerPublisher(dao).publish(server)
   val records=server.calls.single().getJSONArray("records")
   assertEquals(1,records.length());assertTrue(records.getJSONObject(0).isNull("activity_id"))
   assertEquals("2026-10-14T12:00:00Z",records.getJSONObject(0).getString("observed_at"))
  }finally{db.close()}
 }
 @Test fun serverConflictRetainsBlockedEnvelopeWithoutBlindRetriesOrCheckpoint()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  try {
   val dao=db.dao();dao.meta(Metadata(HealthImport.KEY,HealthSnapshotStorage.encode(state())))
   var requests=0
   val server=object:SyncApi {override fun request(path:String,body:JSONObject?):JSONObject {requests++;throw RevisionConflict(JSONObject())}}
   repeat(2){assertTrue(runCatching{TrackerPublisher(dao).publish(server)}.exceptionOrNull() is TrackerPublicationConflict)}
   assertEquals(1,requests);assertNull(dao.meta(TrackerPublisher.CHECKPOINT))
   assertTrue(HealthSnapshotStorage.decode(dao.meta(TrackerPublisher.PENDING)!!).getBoolean("blocked"))
  }finally{db.close()}
 }
}
