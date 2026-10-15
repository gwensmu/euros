package com.gwensmuda.euros

import android.content.Context
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
class BackupRestoreTest {
 private fun archive()=JSONObject().put("schema_version",2).put("units",ExportService.units()).put("records",JSONArray()).put("local_state",JSONObject())
 @Test fun portableStateRoundTripsWithoutSecretsAlarmsOrDuplicateWrites()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>()
  val source=Room.inMemoryDatabaseBuilder(context,EurosDatabase::class.java).build()
  val target=Room.inMemoryDatabaseBuilder(context,EurosDatabase::class.java).build()
  try {
   val dao=source.dao()
   dao.meta(Metadata("draft:food:new","eggs"));dao.meta(Metadata("dismissed_meals:2026-10-15","[\"breakfast\"]"))
   dao.meta(Metadata(ReminderScheduler.KEY,"{\"enabled\":true,\"quiet_start\":\"22:00\",\"quiet_end\":\"07:00\",\"scheduled\":{\"x\":1}}"))
   dao.meta(Metadata("token","secret"));dao.meta(Metadata("cursor","123"));dao.meta(Metadata("server_identity","private"))
   val backup=ExportService(context,dao).document()
   assertFalse(backup.toString().contains("secret"));assertFalse(backup.toString().contains("private"));assertFalse(backup.toString().contains("cursor"))
   val repo=Repository(target);val result=repo.restoreArchive(backup)
   assertEquals(3,result.localImported);assertEquals("eggs",repo.loadDraft("food:new"))
   assertEquals("[\"breakfast\"]",repo.dao.meta("dismissed_meals:2026-10-15"))
   val reminders=JSONObject(repo.dao.meta(ReminderScheduler.KEY)!!)
   assertFalse(reminders.getBoolean("enabled"));assertEquals(0,reminders.getJSONObject("scheduled").length())
   assertEquals("22:00",reminders.getString("quiet_start"));assertTrue(repo.dao.pending().isEmpty())
   repo.saveDraft("food:new","changed")
   assertEquals(3,repo.restoreArchive(backup).localSkipped)
   assertEquals("changed",repo.loadDraft("food:new"))
  }finally{source.close();target.close()}
 }
 @Test fun invalidArchiveRollsBackRecordsAndLocalState()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();val db=Room.inMemoryDatabaseBuilder(context,EurosDatabase::class.java).build()
  try {
   val backup=archive().put("health_included",true).put("health_imports",JSONObject("""{"schema":1,"records":{"w":{"kind":"exercise","source":"polar","source_id":"w","start":"2026-10-15T10:00:00Z","end":"2026-10-15T11:00:00Z","exercise_type":44}},"links":{"missing":"w"}}"""))
   backup.getJSONObject("local_state").put("draft:food:new","test")
   assertTrue(runCatching{Repository(db).restoreArchive(backup)}.isFailure)
   assertTrue(db.dao().allMetadata().isEmpty());assertTrue(db.dao().pending().isEmpty())
   val forbidden=archive();forbidden.getJSONObject("local_state").put("server_identity","other")
   assertTrue(runCatching{Repository(db).restoreArchive(forbidden)}.isFailure)
  }finally{db.close()}
 }
 @Test fun healthCacheRequiresOptInAndNeverOverwritesExistingCache()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();val db=Room.inMemoryDatabaseBuilder(context,EurosDatabase::class.java).build()
  try {
   val backup=archive().put("health_included",true).put("health_imports",JSONObject("{\"schema\":1,\"records\":{},\"links\":{}}"))
   val repo=Repository(db);assertEquals(1,repo.restoreArchive(backup).localImported)
   val saved=db.dao().meta(HealthImport.KEY)
   backup.getJSONObject("health_imports").put("refreshed_at","different")
   assertEquals(1,repo.restoreArchive(backup).localSkipped);assertEquals(saved,db.dao().meta(HealthImport.KEY))
   backup.put("health_included",false);assertTrue(runCatching{repo.restoreArchive(backup)}.isFailure)
  }finally{db.close()}
 }
 @Test fun legacyArchiveDoesNotImportUnvalidatedMetadata()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();val db=Room.inMemoryDatabaseBuilder(context,EurosDatabase::class.java).build()
  try {val backup=archive().put("schema_version",1);backup.getJSONObject("local_state").put("token","unsafe");Repository(db).restoreArchive(backup);assertTrue(db.dao().allMetadata().isEmpty())}finally{db.close()}
 }
}
