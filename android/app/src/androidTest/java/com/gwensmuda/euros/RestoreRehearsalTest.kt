package com.gwensmuda.euros

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** File-backed replacement-device rehearsal, without touching the app DB or providers. */
class RestoreRehearsalTest {
 @Test fun portableFileSurvivesReplacementRestartAndRepeatedMerge()=runBlocking<Unit> {
  val context=ApplicationProvider.getApplicationContext<Context>()
  val prefix="restore-rehearsal-${UUID.randomUUID()}"
  val source=Room.databaseBuilder(context,EurosDatabase::class.java,"$prefix-source").build()
  var target=Room.databaseBuilder(context,EurosDatabase::class.java,"$prefix-target").build()
  val archive=File(context.cacheDir,"$prefix.json")
  val food=JSONObject("""{"consumed_at":"2026-10-14T12:00:00-05:00","local_date":"2026-10-14","raw_text":"rehearsal","nutrients":{"kcal":"100","protein_g":null,"carbs_g":0,"fat_g":null}}""")
  try {
   val original=Repository(source)
   original.upsert("food_entries","preserved",food)
   original.upsert("food_entries","new",food)
   original.upsert("activities","session",JSONObject("""{"title":"Rehearsal","kind":"bjj","status":"done","scheduled_at":"2026-10-14T18:30:00-05:00"}"""))
   original.saveDraft("food:new","Unsaved rehearsal draft")
   source.dao().meta(Metadata(ReminderScheduler.KEY,"""{"enabled":true,"quiet_start":"22:00","quiet_end":"07:00"}"""))
   source.dao().meta(Metadata("nutrition_export_enabled","true"))
   source.dao().meta(Metadata("cursor","secret-cursor"))
   val health=JSONObject("""{"schema":1,"records":{"workout":{"kind":"exercise","source":"fi.polar.polarflow","source_id":"workout","start":"2026-10-14T23:30:00Z","end":"2026-10-15T00:30:00Z","exercise_type":44}},"links":{"session":"workout"},"excluded_links":{"other":["workout"]}}""")
   source.dao().meta(Metadata(HealthImport.KEY,HealthSnapshotStorage.encodeHealth(health)))
   val exporter=ExportService(context,source.dao())
   exporter.write(Uri.fromFile(archive),includeHealth=true)
   val parsed=exporter.read(Uri.fromFile(archive))
   assertFalse(parsed.toString().contains("secret-cursor"))
   assertFalse(parsed.toString().contains("nutrition_export_enabled"))
   val replacement=Repository(target)
   replacement.upsert("food_entries","preserved",food.put("raw_text","Newer local edit"))
   replacement.delete("food_entries","preserved")
   val existingQueue=target.dao().pending()
   val result=replacement.restoreArchive(parsed)
   assertEquals(2,result.imported)
   assertTrue(target.dao().record("food_entries","preserved")!!.deleted)
   assertTrue(target.dao().pending().containsAll(existingQueue))
   assertEquals(health.toString(),HealthSnapshotStorage.decode(target.dao().meta(HealthImport.KEY)!!).toString())
   val records=target.dao().recordsIncludingDeleted();val queue=target.dao().pending()
   target.close()
   target=Room.databaseBuilder(context,EurosDatabase::class.java,"$prefix-target").build()
   val reopened=Repository(target)
   assertEquals(records,target.dao().recordsIncludingDeleted())
   assertEquals(queue,target.dao().pending())
   assertEquals("Unsaved rehearsal draft",reopened.loadDraft("food:new"))
   assertFalse(JSONObject(target.dao().meta(ReminderScheduler.KEY)!!).getBoolean("enabled"))
   assertEquals(0,reopened.restoreArchive(parsed).imported)
   assertEquals(queue,target.dao().pending())
   assertNull(target.dao().meta("cursor"));assertNull(target.dao().meta("nutrition_export_enabled"))
  } finally {source.close();target.close();archive.delete();context.deleteDatabase("$prefix-source");context.deleteDatabase("$prefix-target")}
 }
}
