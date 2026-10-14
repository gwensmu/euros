package com.gwensmuda.euros

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class)
class SyncConnectionTest {
 private class MemoryStore:EnrollmentStore {
  var value:ServerEnrollment?=null;var failRead=false;var failWrite=false
  override fun read():ServerEnrollment?{if(failRead)error("private-secret");return value}
  override fun write(value:ServerEnrollment){if(failWrite)error("private-secret");this.value=value}
  override fun clear(){value=null}
 }
 private class Schedule:SyncScheduling {
  var enables=0;var requests=0;var cancels=0
  override fun enable(){enables++};override fun request(){requests++};override fun cancel(){cancels++}
 }
 private class Transport:SyncApi {
  var error:Exception?=null;var calls=0
  override fun request(path:String,body:JSONObject?):JSONObject {
   calls++;error?.let{throw it}
   return JSONObject("""{"changes":[],"next_cursor":0,"has_more":false}""")
  }
 }
 private fun database()=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
 @Test fun enrollmentSurvivesControllerRestartAndRestoresModelBeforeNetwork()=runBlocking {
  val db=database();val store=MemoryStore();val schedule=Schedule();val api=Transport()
  val first=SyncConnection(Repository(db),store,schedule,{api})
  first.connect("https://example.com/","secret")
  assertEquals("https://example.com",store.value!!.server)
  val calls=api.calls;val repo=Repository(db);val restarted=SyncConnection(repo,store,schedule,{api})
  restarted.initialize()
  assertTrue(restarted.state.value.connected);assertNotNull(restarted.state.value.lastSuccess)
  assertEquals(calls,api.calls)
  repo.interpretFood("one egg")
  assertEquals(calls+1,api.calls)
  db.close()
 }
 @Test fun unsuccessfulEnrollmentDoesNotPersistOrEnableModel()=runBlocking {
  val db=database();val store=MemoryStore();val schedule=Schedule();val api=Transport().apply{error=ApiFailure(401,"secret")}
  val repo=Repository(db);val connection=SyncConnection(repo,store,schedule,{api})
  assertTrue(runCatching{connection.connect("https://example.com","secret")}.isFailure)
  assertNull(store.value);assertEquals(0,schedule.enables);assertFalse(connection.state.value.syncing)
  assertFalse(connection.state.value.error!!.contains("secret"))
  assertTrue(runCatching{repo.interpretFood("egg")}.exceptionOrNull()!!.message!!.startsWith("Connect"))
  db.close()
 }
 @Test fun secureStorageFailureDoesNotLeaveTransientModelConnection()=runBlocking {
  val db=database();val store=MemoryStore().apply{failWrite=true};val api=Transport();val repo=Repository(db)
  val connection=SyncConnection(repo,store,Schedule(),{api})
  assertTrue(runCatching{connection.connect("https://example.com","secret")}.isFailure)
  assertNull(store.value);assertFalse(connection.state.value.connected)
  assertTrue(runCatching{repo.interpretFood("egg")}.isFailure);db.close()
 }
 @Test fun networkFailureRetriesAndRevokedAuthenticationRequiresReconnect()=runBlocking {
  val db=database();val store=MemoryStore();val api=Transport();val repo=Repository(db)
  val connection=SyncConnection(repo,store,Schedule(),{api})
  connection.connect("https://example.com","secret");val last=connection.state.value.lastSuccess
  api.error=java.io.IOException("secret")
  assertEquals(SyncOutcome.RETRY,connection.sync());assertEquals(last,connection.state.value.lastSuccess)
  assertTrue(connection.state.value.connected);assertNotNull(store.value)
  api.error=ApiFailure(401,"secret")
  assertEquals(SyncOutcome.RECONNECT,connection.sync())
  assertTrue(runCatching{repo.interpretFood("egg")}.exceptionOrNull()!!.message!!.startsWith("Connect"))
  api.error=null;assertEquals(SyncOutcome.SUCCESS,connection.sync());assertNull(connection.state.value.error);db.close()
 }
 @Test fun disconnectClearsCredentialsCancelsWorkAndStopsModels()=runBlocking {
  val db=database();val store=MemoryStore();val schedule=Schedule();val api=Transport();val repo=Repository(db)
  val connection=SyncConnection(repo,store,schedule,{api})
  connection.connect("https://example.com","secret");connection.disconnect()
  val calls=api.calls;val requests=schedule.requests
  connection.request();assertEquals(SyncOutcome.RECONNECT,connection.sync())
  assertNull(store.value);assertEquals(1,schedule.cancels);assertEquals(requests,schedule.requests)
  assertEquals(calls,api.calls);assertFalse(connection.state.value.connected)
  assertTrue(runCatching{repo.interpretFood("egg")}.isFailure);db.close()
 }
 @Test fun unreadableCredentialsGiveSafeActionableState()=runBlocking {
  val db=database();val store=MemoryStore().apply{failRead=true};val api=Transport()
  val connection=SyncConnection(Repository(db),store,Schedule(),{api});connection.initialize()
  assertFalse(connection.state.value.connected);assertTrue(connection.state.value.error!!.contains("Reconnect"))
  assertEquals(0,api.calls);db.close()
 }
 @Test fun cancellationIsPropagatedAndBusyStateCleared()=runBlocking {
  val db=database();val api=Transport();val connection=SyncConnection(Repository(db),MemoryStore(),Schedule(),{api})
  connection.connect("https://example.com","secret");api.error=CancellationException()
  assertTrue(runCatching{connection.sync()}.exceptionOrNull() is CancellationException)
  assertFalse(connection.state.value.syncing);assertNull(connection.state.value.error);db.close()
 }
 @Test fun cancelledInitializationCanBeRetried()=runBlocking {
  val db=database();var reads=0
  val store=object:EnrollmentStore {
   override fun read():ServerEnrollment {if(reads++==0)throw CancellationException();return ServerEnrollment("https://example.com","secret")}
   override fun write(value:ServerEnrollment){};override fun clear(){}
  }
  val connection=SyncConnection(Repository(db),store,Schedule(),{Transport()})
  assertTrue(runCatching{connection.initialize()}.exceptionOrNull() is CancellationException)
  connection.initialize();assertTrue(connection.state.value.connected);assertEquals(2,reads);db.close()
 }
 @Test fun cancelledEnrollmentDoesNotEnableUnsavedModelClient()=runBlocking {
  val db=database();val repo=Repository(db)
  val store=object:EnrollmentStore {
   override fun read():ServerEnrollment?=null
   override fun write(value:ServerEnrollment){throw CancellationException()};override fun clear(){}
  }
  val connection=SyncConnection(repo,store,Schedule(),{Transport()})
  assertTrue(runCatching{connection.connect("https://example.com","secret")}.exceptionOrNull() is CancellationException)
  assertTrue(runCatching{repo.interpretFood("egg")}.isFailure)
  assertFalse(connection.state.value.connected);assertFalse(connection.state.value.syncing);db.close()
 }
 @Test fun overlappingSyncAndDisconnectAreSerialized()=runBlocking {
  val db=database();val store=MemoryStore().apply{value=ServerEnrollment("https://example.com","secret")}
  val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
  var calls=0
  val api=object:SyncApi {override fun request(path:String,body:JSONObject?):JSONObject {
   calls++;entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))
   return JSONObject("""{"changes":[],"next_cursor":0,"has_more":false}""")
  }}
  val connection=SyncConnection(Repository(db),store,Schedule(),{api})
  val running=async(Dispatchers.IO){connection.sync()}
  assertTrue(withContext(Dispatchers.IO){entered.await(5,java.util.concurrent.TimeUnit.SECONDS)})
  val disconnect=async(Dispatchers.IO){connection.disconnect()}
  delay(50);assertFalse(disconnect.isCompleted);release.countDown()
  running.await();disconnect.await();val count=calls
  assertEquals(SyncOutcome.RECONNECT,connection.sync());assertEquals(count,calls);assertNull(store.value);db.close()
 }
 @Test fun publicEndpointAliasPreservesOnlyTheKnownExactOrigin() {
  assertEquals("https://euros-lan.example.com",canonicalServerIdentity("https://euros.example.com/"))
  for(other in listOf("http://euros.example.com","https://euros.example.com:443","https://euros.example.com/v1","https://euros.example.com.evil.test","https://other.example","https://euros.example.com?x=1")) {
   assertEquals(other,canonicalServerIdentity(other))
  }
 }
 @Test fun pendingTriggerIgnoresAcknowledgementsButCatchesNewWritesAndRetries() {
  val trigger=PendingSyncTrigger();val first=Pending("one","activities","a",0,"{}");val second=first.copy(commandId="two")
  assertFalse(trigger.changed(emptyList()));assertTrue(trigger.changed(listOf(first)))
  assertFalse(trigger.changed(listOf(first)));assertTrue(trigger.changed(listOf(first,second)))
  assertFalse(trigger.changed(listOf(second)));assertFalse(trigger.changed(listOf(second.copy(error="Conflict"))))
  assertTrue(trigger.changed(listOf(second)));assertFalse(trigger.changed(emptyList()))
 }
 @Test fun trackerFailureDoesNotBlockEnrollmentOrCanonicalSyncFreshness()=runBlocking {
  val db=database()
  try {
   val workout=JSONObject("""{"kind":"exercise","source":"fi.polar.polarflow","source_id":"session","modified_at":"2026-10-14T10:00:00Z","start":"2026-10-14T10:00:00Z","end":"2026-10-14T11:00:00Z","exercise_type":1,"deleted":false}""")
   val health=JSONObject().put("records",JSONObject().put(HealthSnapshots.key(workout),workout)).put("links",JSONObject()).put("refreshed_at","2026-10-14T12:00:00Z")
   db.dao().meta(Metadata(HealthImport.KEY,HealthSnapshotStorage.encode(health)))
   val api=object:SyncApi {override fun request(path:String,body:JSONObject?):JSONObject {
    if(path=="/v1/tracker/import")throw java.io.IOException("offline")
    return JSONObject("""{"changes":[],"next_cursor":0,"has_more":false}""")
   }}
   val store=MemoryStore();val repo=Repository(db);val connection=SyncConnection(repo,store,Schedule(),{api})
   connection.connect("https://example.com","secret")
   assertNotNull(store.value);assertTrue(connection.state.value.connected);assertNotNull(connection.state.value.lastSuccess)
   assertTrue(connection.state.value.error!!.startsWith("Logs synced. Workout sync"))
   assertEquals(SyncOutcome.RETRY,connection.sync())
   assertNotNull(repo.dao.meta(TrackerPublisher.PENDING));assertNull(repo.dao.meta(TrackerPublisher.CHECKPOINT))
  }finally{db.close()}
 }
}
