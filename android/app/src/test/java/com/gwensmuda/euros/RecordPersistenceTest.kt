package com.gwensmuda.euros
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class)
class RecordPersistenceTest {
 private fun food(kcal:String="100.1")=JSONObject("""{"consumed_at":"2026-10-14T12:00:00-05:00","local_date":"2026-10-14","raw_text":"manual","nutrients":{"kcal":"$kcal","protein_g":null,"carbs_g":0,"fat_g":null}}""")
 private fun activity()=JSONObject("""{"title":"Recovery","kind":"recovery","status":"pending","scheduled_at":"2026-10-14T19:00:00-05:00"}""")
 @Test fun unlimitedEditsDeleteAndDraftSurviveReopen()=runBlocking<Unit> {
  val c=ApplicationProvider.getApplicationContext<Context>();c.deleteDatabase("euros.db")
  val db=EurosDatabase.open(c);val r=Repository(db)
  r.upsert("food_entries","meal",food());r.upsert("food_entries","meal",food())
  assertEquals(1,r.dao.pending().size)
  r.upsert("food_entries","meal",food("200"));r.upsert("food_entries","meal",food("300"));r.delete("food_entries","meal")
  val queue=r.dao.pending();assertEquals(listOf(0L,1L,2L,3L),queue.map{it.expectedRevision})
  assertEquals(listOf("upsert","upsert","upsert","delete"),queue.map{it.operation})
  assertEquals(0,Commands.envelope(queue.last()).getJSONObject("payload").length())
  assertTrue(r.dao.record("food_entries","meal")!!.deleted)
  assertTrue(runCatching{r.upsert("food_entries","meal",food())}.isFailure)
  r.saveDraft("food-entry","200g rice");db.close()
  val reopened=EurosDatabase.open(c);val rr=Repository(reopened)
  assertEquals(queue,rr.dao.pending());assertEquals("200g rice",rr.loadDraft("food-entry"))
  rr.clearDraft("food-entry");assertNull(rr.loadDraft("food-entry"))
  reopened.close();c.deleteDatabase("euros.db")
 }
 @Test fun completeMoveSkipChainPreservesIdentityAndSnapshot()=runBlocking<Unit> {
  val c=ApplicationProvider.getApplicationContext<Context>();c.deleteDatabase("euros.db")
  val db=EurosDatabase.open(c);val r=Repository(db)
  r.upsert("activities","step",activity().put("plan_snapshot",JSONObject().put("original",true)))
  val original=r.dao.record("activities","step")!!
  r.complete(original,"done");r.move(original,"2026-11-02T10:00:00-06:00");r.complete(original,"skipped")
  val local=r.dao.record("activities","step")!!;val p=JSONObject(local.payload)
  assertEquals("2026-11-02T10:00:00-06:00",p.getString("scheduled_at"))
  assertEquals("2026-10-14T19:00:00-05:00",p.getJSONArray("move_history").getJSONObject(0).getString("from"))
  assertTrue(p.getJSONObject("plan_snapshot").getBoolean("original"));assertEquals("skipped",p.getString("status"))
  assertEquals(listOf(0L,1L,2L,3L),r.dao.pending().map{it.expectedRevision})
  db.close();c.deleteDatabase("euros.db")
 }
 @Test fun syncEntireChainIncludingDelete()=runBlocking<Unit> {
  val c=ApplicationProvider.getApplicationContext<Context>();c.deleteDatabase("euros.db")
  val db=EurosDatabase.open(c);val r=Repository(db)
  r.upsert("food_entries","meal",food());r.upsert("food_entries","meal",food("200"));r.delete("food_entries","meal")
  val posted=mutableListOf<Long>();var latest=JSONObject()
  val api=object:SyncApi {
   override fun request(path:String,body:JSONObject?):JSONObject {
    if(body==null) return JSONObject().put("changes",JSONArray()).put("next_cursor",3).put("has_more",false)
    val rev=body.getLong("expected_revision");posted.add(rev)
    if(body.getString("operation")=="upsert") latest=body.getJSONObject("payload")
    return JSONObject().put("record",JSONObject().put("entity_type","food_entries").put("entity_id","meal").put("revision",rev+1).put("deleted",body.getString("operation")=="delete").put("payload",latest))
   }
  }
  r.sync(api);assertEquals(listOf(0L,1L,2L),posted);assertTrue(r.dao.pending().isEmpty())
  assertTrue(r.dao.record("food_entries","meal")!!.deleted);assertEquals(3L,r.dao.record("food_entries","meal")!!.revision)
  db.close();c.deleteDatabase("euros.db")
 }
 @Test fun hydrationCompletionDoesNotInventVolumeAndExistingAmountsRemainValid()=runBlocking<Unit> {
  val c=ApplicationProvider.getApplicationContext<Context>();c.deleteDatabase("euros.db")
  val db=EurosDatabase.open(c);val r=Repository(db)
  val p=activity().put("recovery_step_type","hydration")
  r.upsert("activities","water",p)
  r.complete(r.dao.record("activities","water")!!,"done")
  val completed=JSONObject(r.dao.record("activities","water")!!.payload)
  assertEquals("done",completed.getString("status"));assertFalse(completed.has("recovery_intake"))
  assertEquals(2,r.dao.pending().size)
  val supplement=activity().put("recovery_step_type","supplement").put("status","done")
  assertTrue(runCatching{RecordValidation.validate("activities",supplement)}.isFailure)
  p.put("status","done").put("recovery_intake",JSONObject().put("kind","hydration").put("amount",500).put("unit","ml"))
  r.upsert("activities","water",p)
  r.complete(r.dao.record("activities","water")!!,"done")
  assertEquals(500,JSONObject(r.dao.record("activities","water")!!.payload).getJSONObject("recovery_intake").getInt("amount"))
  r.complete(r.dao.record("activities","water")!!,"skipped")
  assertFalse(JSONObject(r.dao.record("activities","water")!!.payload).has("recovery_intake"))
  db.close();c.deleteDatabase("euros.db")
 }
 @Test fun invalidInputsDoNotWrite()=runBlocking<Unit> {
  val c=ApplicationProvider.getApplicationContext<Context>();c.deleteDatabase("euros.db")
  val db=EurosDatabase.open(c);val r=Repository(db)
  for(value in listOf("-1","1e999","0.0000001","NaN")) assertTrue(runCatching{r.upsert("food_entries","x",food(value))}.isFailure)
  assertTrue(runCatching{r.upsert("food_entries","x",food().put("local_date","2026-10-15"))}.isFailure)
  assertTrue(runCatching{r.upsert("activities","x",activity().put("scheduled_at","2026-10-14T19:00"))}.isFailure)
  assertTrue(r.dao.pending().isEmpty());assertNull(r.dao.record("food_entries","x"))
  db.close();c.deleteDatabase("euros.db")
 }
 @Test fun weightSelectionAndChildrenMoveAreAtomic()=runBlocking<Unit> {
  val c=ApplicationProvider.getApplicationContext<Context>();c.deleteDatabase("euros.db")
  val db=EurosDatabase.open(c);val r=Repository(db)
  val weight=JSONObject("""{"measured_at":"2026-10-18T08:00:00-05:00","local_date":"2026-10-18","weight_lb":"164","weekly_anchor":"2026-10-12"}""")
  r.saveWeight("first",weight);r.saveWeight("second",weight)
  assertFalse(JSONObject(r.dao.record("weight_entries","first")!!.payload).has("weekly_anchor"))
  assertEquals(listOf("first","first","second"),r.dao.pending().map{it.id})
  r.upsert("activities","parent",activity())
  r.upsert("activities","pending",activity().put("parent_id","parent").put("scheduled_at","2026-10-14T20:00:00-05:00"))
  r.upsert("activities","done",activity().put("parent_id","parent").put("status","done").put("scheduled_at","2026-10-14T20:00:00-05:00"))
  r.move(r.dao.record("activities","parent")!!,"2026-10-15T19:00:00-05:00")
  assertEquals("2026-10-15T20:00-05:00",JSONObject(r.dao.record("activities","pending")!!.payload).getString("scheduled_at"))
  assertEquals("2026-10-14T20:00:00-05:00",JSONObject(r.dao.record("activities","done")!!.payload).getString("scheduled_at"))
  assertEquals(8,r.dao.pending().size)
  db.close();c.deleteDatabase("euros.db")
 }
 @Test fun deletionExportRetainsTombstoneAndPendingOperation()=runBlocking<Unit> {
  val c=ApplicationProvider.getApplicationContext<Context>();c.deleteDatabase("euros.db")
  val db=EurosDatabase.open(c);val r=Repository(db)
  r.upsert("food_entries","meal",food());r.delete("food_entries","meal");r.delete("food_entries","meal")
  val snapshot=r.dao.exportSnapshot();assertEquals(1,snapshot.records.size);assertTrue(snapshot.records.single().deleted)
  assertEquals(2,snapshot.pending.size);assertEquals("delete",snapshot.pending.last().operation)
  db.close();c.deleteDatabase("euros.db")
 }

 @Test fun migrationPreservesVersionOneOutboxAndDefaultsOperation()=runBlocking<Unit> {
  val c=ApplicationProvider.getApplicationContext<Context>();c.deleteDatabase("euros.db")
  val file=c.getDatabasePath("euros.db");file.parentFile!!.mkdirs()
  val legacy=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file,null)
  legacy.execSQL("CREATE TABLE records (type TEXT NOT NULL,id TEXT NOT NULL,revision INTEGER NOT NULL,payload TEXT NOT NULL,deleted INTEGER NOT NULL,PRIMARY KEY(type,id))")
  legacy.execSQL("CREATE TABLE outbox (commandId TEXT NOT NULL PRIMARY KEY,type TEXT NOT NULL,id TEXT NOT NULL,expectedRevision INTEGER NOT NULL,payload TEXT NOT NULL,createdAt INTEGER NOT NULL,error TEXT)")
  legacy.execSQL("CREATE TABLE metadata (`key` TEXT NOT NULL PRIMARY KEY,value TEXT NOT NULL)")
  legacy.execSQL("INSERT INTO outbox VALUES ('stable','food_entries','meal',0,'{}',123,NULL)")
  legacy.version=1;legacy.close()
  val migrated=EurosDatabase.open(c)
  val pending=migrated.dao().pending().single()
  assertEquals("stable",pending.commandId);assertEquals("upsert",pending.operation);assertEquals(123L,pending.createdAt)
  migrated.close();c.deleteDatabase("euros.db")
 }

 @Test fun restoreIsAtomicIdempotentAndPreservesLocalTombstones()=runBlocking<Unit> {
  val c=ApplicationProvider.getApplicationContext<Context>();c.deleteDatabase("euros.db")
  val db=EurosDatabase.open(c);val r=Repository(db)
  fun exported(id:String,p:JSONObject)=JSONObject().put("entity_type","food_entries").put("entity_id",id).put("revision",8).put("deleted",false).put("payload",p)
  val archive=JSONArray().put(exported("one",food())).put(exported("two",food("200")))
  assertEquals(RestoreResult(2,0),r.restoreRecords(archive));assertEquals(RestoreResult(0,2),r.restoreRecords(archive))
  assertEquals(listOf(0L,0L),r.dao.pending().map{it.expectedRevision})
  r.delete("food_entries","one");assertEquals(RestoreResult(0,2),r.restoreRecords(archive));assertTrue(r.dao.record("food_entries","one")!!.deleted)
  val before=r.dao.pending()
  assertTrue(runCatching{r.restoreRecords(JSONArray().put(exported("new",food())).put(exported("bad",food("-1"))))}.isFailure)
  assertNull(r.dao.record("food_entries","new"));assertEquals(before,r.dao.pending())
  assertTrue(runCatching{r.restoreRecords(JSONArray().put(exported("same",food())).put(exported("same",food())))}.isFailure)
  db.close();c.deleteDatabase("euros.db")
 }
 @Test fun staleDraftCannotOverwriteNewServerRevision()=runBlocking<Unit> {
  val c=ApplicationProvider.getApplicationContext<Context>();c.deleteDatabase("euros.db")
  val db=EurosDatabase.open(c);val r=Repository(db)
  r.dao.put(Record("food_entries","meal",2,food("200").toString()))
  assertTrue(runCatching{r.upsert("food_entries","meal",food("100"),expectedRevision=1)}.isFailure)
  assertTrue(r.dao.pending().isEmpty());assertEquals("200",JSONObject(r.dao.record("food_entries","meal")!!.payload).getJSONObject("nutrients").getString("kcal"))
  db.close();c.deleteDatabase("euros.db")
 }

 @Test fun offlineStaleEditorCannotReplaceNewerLocalPayload()=runBlocking<Unit> {
  val c=ApplicationProvider.getApplicationContext<Context>();c.deleteDatabase("euros.db")
  val db=EurosDatabase.open(c);val r=Repository(db)
  r.upsert("food_entries","meal",food("100"))
  val old=r.dao.record("food_entries","meal")!!
  r.upsert("food_entries","meal",food("200"),expectedRevision=old.revision,expectedPayload=old.payload)
  assertTrue(runCatching{r.upsert("food_entries","meal",food("300"),expectedRevision=old.revision,expectedPayload=old.payload)}.isFailure)
  assertTrue(runCatching{r.delete("food_entries","meal",expectedRevision=old.revision,expectedPayload=old.payload)}.isFailure)
  assertEquals(2,r.dao.pending().size)
  db.close();c.deleteDatabase("euros.db")
 }

 @Test fun importedWeightRepeatedSelectionUsesOneRecordAndStaleEditFails()=runBlocking<Unit> {
  val c=ApplicationProvider.getApplicationContext<Context>();c.deleteDatabase("euros.db")
  val db=EurosDatabase.open(c);val r=Repository(db)
  val weight=JSONObject("""{"measured_at":"2026-10-18T08:00:00-05:00","local_date":"2026-10-18","weight_lb":"164","weekly_anchor":"2026-10-12","source_key":"tracker:weight:one"}""")
  r.saveWeight("first",weight);r.saveWeight("new-click",weight)
  assertEquals(1,r.dao.recordsIncludingDeleted().size);assertEquals(1,r.dao.pending().size)
  val original=r.dao.record("weight_entries","first")!!
  r.saveWeight("first",JSONObject(weight.toString()).put("weight_lb","163.8"),0,original.payload)
  assertTrue(runCatching{r.saveWeight("first",weight,0,original.payload)}.isFailure)
  r.delete("weight_entries","first")
  assertTrue(runCatching{r.saveWeight("another-click",weight)}.isFailure)
  db.close();c.deleteDatabase("euros.db")
 }
 @Test fun exactSavedDraftRetryAfterSyncDoesNotRequireOldRevision()=runBlocking<Unit> {
  val c=ApplicationProvider.getApplicationContext<Context>();c.deleteDatabase("euros.db")
  val db=EurosDatabase.open(c);val r=Repository(db)
  r.dao.put(Record("food_entries","meal",1,food().toString()))
  r.upsert("food_entries","meal",food(),expectedRevision=0)
  assertTrue(r.dao.pending().isEmpty())
  assertTrue(runCatching{r.upsert("food_entries","meal",food("200"),expectedRevision=0)}.isFailure)
  db.close();c.deleteDatabase("euros.db")
 }
 @Test fun movingSessionMovesLinkedGroupAndNestedPendingStepsOnce()=runBlocking<Unit> {
  val c=ApplicationProvider.getApplicationContext<Context>();c.deleteDatabase("euros.db")
  val db=EurosDatabase.open(c);val r=Repository(db)
  r.upsert("activities","session",activity().put("kind","bjj"))
  r.upsert("activities","group",activity().put("linked_session_id","session"))
  r.upsert("activities","step",activity().put("parent_id","group").put("linked_session_id","session"))
  r.upsert("activities","done",activity().put("parent_id","group").put("status","done"))
  r.move(r.dao.record("activities","session")!!,"2026-10-15T19:00:00-05:00")
  for(id in listOf("group","step")) {
   val p=JSONObject(r.dao.record("activities",id)!!.payload)
   assertEquals("2026-10-15T19:00-05:00",p.getString("scheduled_at"));assertEquals(1,p.getJSONArray("move_history").length())
  }
  assertEquals("2026-10-14T19:00:00-05:00",JSONObject(r.dao.record("activities","done")!!.payload).getString("scheduled_at"))
  assertEquals(7,r.dao.pending().size)
  db.close();c.deleteDatabase("euros.db")
 }

}
