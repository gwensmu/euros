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
class MascotIntegrationTest {
 private fun database()=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
 private fun activity()=Record("activities","session",1,"""{"title":"Weights","kind":"weights","scheduled_at":"2026-10-15T16:00:00-05:00","status":"pending"}""")
 @Test fun completedLocalActionReactsOnceAndDoesNotReplayOnReopening()=runBlocking {
  val db=database();val repo=Repository(db);val record=activity();db.dao().put(record)
  var reactions=0;val watch=launch(start=CoroutineStart.UNDISPATCHED){repo.localCompletions.collect{reactions++}}
  repo.complete(record,"done");yield();assertEquals(1,reactions)
  repo.complete(record,"done");yield();assertEquals(1,reactions)
  val completed=db.dao().record(record.type,record.id)!!
  repo.upsert(record.type,record.id,JSONObject(completed.payload).put("title","Weights corrected"));yield();assertEquals(1,reactions)
  watch.cancelAndJoin()
  val reopened=launch(start=CoroutineStart.UNDISPATCHED){repo.localCompletions.collect{reactions++}}
  yield();assertEquals(1,reactions);reopened.cancelAndJoin();db.close()
 }
 @Test fun skipUsesActualTimestampAndDoneClearsIt()=runBlocking {
  val db=database();val repo=Repository(db);val record=activity();db.dao().put(record)
  var reactions=0;val watch=launch(start=CoroutineStart.UNDISPATCHED){repo.localCompletions.collect{reactions++}}
  repo.complete(record,"skipped");yield()
  val skipped=JSONObject(db.dao().record(record.type,record.id)!!.payload)
  assertTrue(skipped.has("skipped_at"));java.time.OffsetDateTime.parse(skipped.getString("skipped_at"))
  assertFalse(skipped.has("completed_at"));assertEquals(0,reactions)
  repo.complete(record,"done");yield()
  val done=JSONObject(db.dao().record(record.type,record.id)!!.payload)
  assertFalse(done.has("skipped_at"));assertTrue(done.has("completed_at"));assertEquals(1,reactions)
  watch.cancelAndJoin();db.close()
 }
 @Test fun validatedIntakeCompletionReactsButFailedSaveDoesNot()=runBlocking {
  val db=database();val repo=Repository(db);val record=activity();db.dao().put(record)
  var reactions=0;val watch=launch(start=CoroutineStart.UNDISPATCHED){repo.localCompletions.collect{reactions++}}
  assertTrue(runCatching{repo.upsert(record.type,record.id,JSONObject(record.payload).put("status","done"),expectedRevision=99)}.isFailure)
  yield();assertEquals(0,reactions)
  repo.upsert(record.type,record.id,JSONObject(record.payload).put("status","done"),expectedRevision=1)
  yield();assertEquals(1,reactions);watch.cancelAndJoin();db.close()
 }
 @Test fun foodLoggingReactsToNewRecordsRegardlessOfAmountsNotCorrectionsOrDraftReplay()=runBlocking {
  val db=database();val repo=Repository(db)
  var reactions=0;val watch=launch(start=CoroutineStart.UNDISPATCHED){repo.localCompletions.collect{reactions++}}
  fun food(kcal:Any)=JSONObject("""{"consumed_at":"2026-10-14T12:00:00-05:00","local_date":"2026-10-14","raw_text":"food","nutrients":{"kcal":null,"protein_g":null,"carbs_g":null,"fat_g":null}}""").apply{getJSONObject("nutrients").put("kcal",kcal)}
  val unknown=food(JSONObject.NULL)
  repo.upsert("food_entries","one",unknown);yield();assertEquals(1,reactions)
  repo.upsert("food_entries","one",unknown);yield();assertEquals(1,reactions)
  repo.upsert("food_entries","one",food(800));yield();assertEquals(1,reactions)
  repo.upsert("food_entries","two",food(20));yield();assertEquals(2,reactions)
  repo.upsert("food_entries","three",food(1200));yield();assertEquals(3,reactions)
  watch.cancelAndJoin();db.close()
 }
 @Test fun weightLoggingReactsToNewMeasurementsNotChangesOrDuplicateTrackerReading()=runBlocking {
  val db=database();val repo=Repository(db)
  var reactions=0;val watch=launch(start=CoroutineStart.UNDISPATCHED){repo.localCompletions.collect{reactions++}}
  fun weight(lb:Int)=JSONObject("""{"measured_at":"2026-10-18T08:00:00-05:00","local_date":"2026-10-18","weight_lb":$lb}""")
  repo.saveWeight("one",weight(160));yield();assertEquals(1,reactions)
  repo.saveWeight("one",weight(160));yield();assertEquals(1,reactions)
  repo.saveWeight("one",weight(165));yield();assertEquals(1,reactions)
  val imported=weight(170).put("source_key","test-reading")
  repo.saveWeight("two",imported);yield();assertEquals(2,reactions)
  repo.saveWeight("different-id",imported);yield();assertEquals(2,reactions)
  repo.saveWeight("three",weight(180));yield();assertEquals(3,reactions)
  watch.cancelAndJoin();db.close()
 }
 @Test fun atlasCellsCoverOddDimensionsWithoutClippingOrOverlap() {
  val poses=listOf(MascotPose.IDLE,MascotPose.PLEASED,MascotPose.JUDGY,MascotPose.TRAINING,MascotPose.RECOVERY,MascotPose.REST)
  val cells=poses.map{weaselAtlasCell(1537,1025,it)}
  assertEquals(1537*1025,cells.sumOf{it.size.width*it.size.height})
  assertEquals(0,cells[0].offset.x);assertEquals(0,cells[0].offset.y)
  assertEquals(cells[0].size.width,cells[1].offset.x)
  assertEquals(cells[1].offset.x+cells[1].size.width,cells[2].offset.x)
  assertEquals(cells[0].size.height,cells[3].offset.y)
  assertEquals(1537,cells[5].offset.x+cells[5].size.width)
  assertEquals(1025,cells[5].offset.y+cells[5].size.height)
 }
}
