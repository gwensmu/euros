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
@Config(sdk=[28],application=android.app.Application::class)
class PersistenceTest {
 private fun authored()=org.json.JSONArray("""[{"command_id":"create-authored","entity_type":"activities","entity_id":"authored","expected_revision":0,"operation":"upsert","payload":{"title":"Weights","kind":"weights","scheduled_at":"2026-10-15T16:00:00-05:00","status":"pending"}}]""")
 @Test fun creationConflictBlocksChainedEditAndDiscardClearsWholeChain()=runBlocking<Unit> {
  val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("euros.db")
  val db=EurosDatabase.open(context);val repo=Repository(db)
  repo.bootstrapAuthoredCommands(authored());repo.complete(db.dao().record("activities","authored")!!,"done")
  val remote=org.json.JSONObject("""{"entity_type":"activities","entity_id":"authored","revision":3,"deleted":false,"payload":{"status":"skipped"}}""")
  var posts=0
  val api=object:SyncApi {
   override fun request(path:String,body:org.json.JSONObject?):org.json.JSONObject {
    if(body!=null) { posts++;throw RevisionConflict(remote) }
    return org.json.JSONObject().put("changes",org.json.JSONArray().put(remote)).put("next_cursor",3).put("has_more",false)
   }
  }
  repo.sync(api)
  assertEquals(1,posts);assertEquals(2,db.dao().pending().size)
  assertEquals("done",org.json.JSONObject(db.dao().record("activities","authored")!!.payload).getString("status"))
  repo.acceptServer(db.dao().pending().first())
  assertTrue(db.dao().pending().isEmpty());assertEquals("skipped",org.json.JSONObject(db.dao().record("activities","authored")!!.payload).getString("status"))
  db.close();context.deleteDatabase("euros.db")
 }
 @Test fun bootstrapAndOfflineCompletionSurviveRestartAndReplay()=runBlocking<Unit> {
  val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("euros.db")
  val db=EurosDatabase.open(context);val repo=Repository(db)
  assertEquals(1,repo.bootstrapAuthoredCommands(authored()))
  val activity=db.dao().record("activities","authored")!!
  repo.complete(activity,"done")
  val chain=db.dao().pending();assertEquals(listOf(0L,1L),chain.map{it.expectedRevision})
  assertEquals("pending",org.json.JSONObject(chain.first().payload).getString("status"))
  assertEquals("done",org.json.JSONObject(chain.last().payload).getString("status"))
  assertEquals(0,repo.bootstrapAuthoredCommands(authored()))
  db.close()
  val reopened=EurosDatabase.open(context);val restarted=Repository(reopened)
  assertEquals(0,restarted.bootstrapAuthoredCommands(authored()))
  assertEquals(chain,reopened.dao().pending())
  assertEquals("done",org.json.JSONObject(reopened.dao().record("activities","authored")!!.payload).getString("status"))
  reopened.close();context.deleteDatabase("euros.db")
 }
 @Test fun acknowledgedCreationPreservesOfflineCompletionThroughInterruptedSync()=runBlocking<Unit> {
  val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("euros.db")
  val db=EurosDatabase.open(context);val repo=Repository(db)
  repo.bootstrapAuthoredCommands(authored());repo.complete(db.dao().record("activities","authored")!!,"done")
  val edit=db.dao().pending().last()
  val api=object:SyncApi {
   override fun request(path:String,body:org.json.JSONObject?):org.json.JSONObject {
    if(body==null) return org.json.JSONObject("""{"changes":[],"next_cursor":0,"has_more":false}""")
    if(body.getLong("expected_revision")==1L) throw java.io.IOException("Interrupted upload")
    return org.json.JSONObject().put("record",org.json.JSONObject().put("entity_type","activities").put("entity_id","authored").put("revision",1).put("deleted",false).put("payload",body.getJSONObject("payload")))
   }
  }
  assertTrue(runCatching{repo.sync(api)}.isFailure)
  assertEquals(edit,db.dao().pending().single())
  val local=db.dao().record("activities","authored")!!
  assertEquals(1L,local.revision);assertEquals("done",org.json.JSONObject(local.payload).getString("status"))
  val retry=object:SyncApi {
   override fun request(path:String,body:org.json.JSONObject?):org.json.JSONObject {
    if(body==null) return org.json.JSONObject("""{"changes":[],"next_cursor":2,"has_more":false}""")
    assertEquals(edit.commandId,body.getString("command_id"));assertEquals(1L,body.getLong("expected_revision"))
    return org.json.JSONObject().put("record",org.json.JSONObject().put("entity_type","activities").put("entity_id","authored").put("revision",2).put("deleted",false).put("payload",body.getJSONObject("payload")))
   }
  }
  repo.sync(retry);assertTrue(db.dao().pending().isEmpty());assertEquals(2L,db.dao().record("activities","authored")!!.revision)
  assertEquals(0,repo.bootstrapAuthoredCommands(authored()))
  db.dao().put(db.dao().record("activities","authored")!!.copy(deleted=true,revision=3))
  assertEquals(0,repo.bootstrapAuthoredCommands(authored()));assertTrue(db.dao().record("activities","authored")!!.deleted)
  db.close();context.deleteDatabase("euros.db")
 }

 @Test fun pullCannotOverwritePendingPhoneEditAndRetriesKeepIdentity()=runBlocking<Unit> {
  val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("euros.db")
  val db=EurosDatabase.open(context);val repo=Repository(db)
  val r=Record("activities","x",1,"""{"title":"Activity","kind":"weights","scheduled_at":"2026-10-14T09:00:00-05:00","status":"pending"}""")
  db.dao().put(r);repo.complete(r,"done")
  val command=db.dao().pending().single()
  val remote=org.json.JSONObject("""{"entity_type":"activities","entity_id":"x","revision":2,"deleted":false,"payload":{"status":"skipped"}}""")
  val api=object:SyncApi {
   override fun request(path:String,body:org.json.JSONObject?):org.json.JSONObject {
    if(body!=null) { assertEquals(command.commandId,body.getString("command_id"));throw RevisionConflict(remote) }
    return org.json.JSONObject().put("changes",org.json.JSONArray().put(remote)).put("next_cursor",2).put("has_more",false)
   }
  }
  repo.sync(api)
  assertTrue(db.dao().record("activities","x")!!.payload.contains("done"))
  assertNotNull(db.dao().pending().single().error)
  assertEquals("2",db.dao().meta("cursor"))
  repo.acceptServer(db.dao().pending().single())
  assertEquals(2L,db.dao().record("activities","x")!!.revision)
  assertTrue(db.dao().pending().isEmpty())
  db.close();context.deleteDatabase("euros.db")
 }

 @Test fun completionAndQueueSurviveDatabaseReopen()=runBlocking<Unit> {
  val context=ApplicationProvider.getApplicationContext<Context>()
  context.deleteDatabase("euros.db")
  val db=EurosDatabase.open(context)
  val r=Record("activities","weights-one",3,"""{"title":"Weights","kind":"weights","scheduled_at":"2026-10-14T09:00:00-05:00","status":"pending"}""")
  db.dao().put(r)
  Repository(db).complete(r,"done")
  val command=db.dao().pending().single()
  db.close()
  val reopened=EurosDatabase.open(context)
  assertEquals(command,reopened.dao().pending().single())
  assertTrue(reopened.dao().record("activities",r.id)!!.payload.contains("done"))
  assertEquals(3L,reopened.dao().record("activities",r.id)!!.revision)
  // A repeated edit cannot replace the durable command's idempotency key/revision.
  Repository(reopened).complete(r,"skipped")
  assertEquals(command.commandId,reopened.dao().pending().first().commandId)
  assertEquals(listOf(3L,4L),reopened.dao().pending().map{it.expectedRevision})
  reopened.close();context.deleteDatabase("euros.db")
 }
 @Test fun resolvingConflictRequiresRemoteRecordAndIsAtomic()=runBlocking<Unit> {
  val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("euros.db")
  val db=EurosDatabase.open(context);val repo=Repository(db)
  val r=Record("activities","x",1,"""{"title":"Activity","kind":"weights","scheduled_at":"2026-10-14T09:00:00-05:00","status":"pending"}""")
  db.dao().put(r);repo.complete(r,"done")
  val pending=db.dao().pending().single()
  assertTrue(runCatching{repo.acceptServer(pending)}.isFailure)
  assertEquals(1,db.dao().pending().size)
  db.dao().meta(Metadata("conflict:${pending.commandId}","""{"entity_type":"activities","entity_id":"x","revision":2,"deleted":false,"payload":{"status":"skipped"}}"""))
  repo.acceptServer(pending)
  assertTrue(db.dao().pending().isEmpty());assertEquals(2L,db.dao().record("activities","x")!!.revision)
  assertTrue(db.dao().record("activities","x")!!.payload.contains("skipped"))
  db.close();context.deleteDatabase("euros.db")
 }
}
