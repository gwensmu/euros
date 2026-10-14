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
class BootstrapProtocolTest {
 private fun command(id:String,parent:String="",at:String="2026-10-14T08:05:00-05:00")=JSONObject().put("command_id","create-$id").put("entity_type","activities").put("entity_id",id).put("expected_revision",0).put("operation","upsert").put("payload",JSONObject().put("title",id).put("kind","recovery").put("status","pending").put("scheduled_at",at).put("time_zone","America/Chicago").put("parent_id",parent))
 private fun commands()=JSONArray().put(command("child","parent")).put(command("parent",at="2026-10-14T08:00:00-05:00"))
 @Test fun newChildrenFollowMovedParentAcrossDstWithoutChangingExistingSnapshots()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  try {
   val repo=Repository(db)
   val parent=Record("activities","parent",7,command("parent",at="2026-11-04T09:00:00-06:00").getJSONObject("payload").put("status","done").put("source_snapshot","preserved").toString())
   db.dao().put(parent)
   assertEquals(1,repo.bootstrapAuthoredCommands(commands()))
   assertEquals(parent,db.dao().record("activities","parent"))
   val child=db.dao().record("activities","child")!!
   assertEquals("2026-11-04T09:05-06:00",JSONObject(child.payload).getString("scheduled_at"))
   val queued=db.dao().pending().single();assertEquals("create-child",queued.commandId);assertEquals(child.payload,queued.payload)
   assertEquals(0,repo.bootstrapAuthoredCommands(commands()));assertEquals(child,db.dao().record("activities","child"))
  }finally{db.close()}
 }
 @Test fun deletedParentSuppressesNewDescendantsButLeavesExistingChildUntouched()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  try {
   val repo=Repository(db)
   db.dao().put(Record("activities","parent",3,"{}",true))
   val existing=Record("activities","old",2,command("old","parent").getJSONObject("payload").put("status","done").toString())
   db.dao().put(existing)
   val list=commands().put(command("grandchild","child")).put(command("old","parent"))
   assertEquals(0,repo.bootstrapAuthoredCommands(list));assertNull(db.dao().record("activities","child"));assertNull(db.dao().record("activities","grandchild"))
   assertEquals(existing,db.dao().record("activities","old"));assertTrue(db.dao().pending().isEmpty())
  }finally{db.close()}
 }
 @Test fun danglingOrCyclicParentsFailAtomicallyRegardlessOfCommandOrder()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  try {
   val repo=Repository(db)
   for(list in listOf(JSONArray().put(command("valid")).put(command("orphan","missing")),JSONArray().put(command("a","b")).put(command("b","a")))) {
    try {repo.bootstrapAuthoredCommands(list);fail("Invalid graph accepted")}catch(expected:IllegalArgumentException){}
    assertTrue(db.dao().recordsIncludingDeleted().isEmpty());assertTrue(db.dao().pending().isEmpty())
   }
  }finally{db.close()}
 }
 @Test fun childFirstInputCreatesParentFirstAndPreservesExistingChildEdits()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  try {
   val repo=Repository(db);assertEquals(2,repo.bootstrapAuthoredCommands(commands()))
   val edited=db.dao().record("activities","child")!!.copy(payload=command("child","parent",at="2026-10-15T12:00:00-05:00").getJSONObject("payload").put("status","skipped").toString())
   db.dao().put(edited);val before=db.dao().pending()
   assertEquals(0,repo.bootstrapAuthoredCommands(commands()));assertEquals(edited,db.dao().record("activities","child"));assertEquals(before,db.dao().pending())
  }finally{db.close()}
 }
}
