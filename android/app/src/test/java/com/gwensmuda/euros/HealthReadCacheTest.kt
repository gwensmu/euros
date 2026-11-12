package com.gwensmuda.euros
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class)
class HealthReadCacheTest {
 private fun snapshot(bpm:Int=140,linked:Boolean=true,deleted:Boolean=false):JSONObject {
  val start=Instant.parse("2026-11-12T18:00:00Z")
  val workout=JSONObject().put("kind","exercise").put("source",HealthSources.POLAR).put("source_id","exercise").put("start",start.toString()).put("end",start.plusSeconds(600).toString()).put("exercise_type",44).put("deleted",deleted)
  fun hr(at:Instant,value:Int,bounds:Boolean)=JSONObject().put("kind","heart_rate").put("source",HealthSources.POLAR).put("modified_at",at.toString()).put("samples",JSONArray().apply{for(i in 0..10)put(JSONObject().put("time",at.plusSeconds(60L*i).toString()).put("bpm",value))}).apply{if(bounds){put("start",at.toString());put("end",at.plusSeconds(600).toString())}}
  return JSONObject().put("records",JSONObject().put("exercise",workout).put("hr",hr(start,bpm,true)).put("outside",hr(start.minusSeconds(86400),100,true)).put("legacy",hr(start.plusSeconds(30),bpm,false)))
   .put("links",JSONObject().apply{if(linked)put("activity","exercise")})
 }
 @Test fun readCachePreservesFullRecapAndInvalidatesCorrectionsLinksAndClear()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  try {
   val dao=db.dao();val cache=HealthReadCache(dao)
   suspend fun save(s:JSONObject){dao.meta(Metadata(HealthImport.KEY,HealthSnapshotStorage.encode(s)))}
   val first=snapshot();save(first)
   val result=cache.recap("activity")!!
   assertEquals(HealthRecaps.buildRecap(first,"exercise"),result)
   assertSame(result,cache.recapForKey("exercise"))
   val stored=dao.meta(HealthImport.KEY);cache.weightCandidates();cache.sourceCounts();assertEquals(stored,dao.meta(HealthImport.KEY));assertTrue(dao.pending().isEmpty())
   save(snapshot(bpm=165));val corrected=cache.recap("activity")!!
   assertNotSame(result,corrected);assertEquals(165.0,corrected.averageBpm!!,0.0)
   save(snapshot(linked=false));assertNull(cache.recap("activity"));assertNotNull(cache.recapForKey("exercise"))
   save(snapshot(deleted=true));assertTrue(cache.recap("activity")!!.session.deleted);assertTrue(cache.recap("activity")!!.samples.isEmpty())
   dao.removeMeta(HealthImport.KEY);assertNull(cache.recap("activity"));assertTrue(cache.weightCandidates().isEmpty())
  } finally {db.close()}
 }
 @Test fun selectedDecoderSkipsUnrelatedSamplesAndPreservesLegacyBounds() {
  val s=snapshot();for(raw in listOf(s.toString(),HealthSnapshotStorage.encode(s))) {
   val selected=HealthSnapshotStorage.decodeSelected(raw,setOf("hr","legacy"))
   assertFalse(selected.getJSONObject("records").getJSONObject("outside").has("samples"))
   assertEquals(HealthRecaps.buildRecap(s,"exercise"),HealthRecaps.buildRecap(selected,"exercise"))
  }
 }
 @Test fun chunkReadTokenDetectsSameSizeCorrectionsAndSupportsLegacyHeaders()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  try {
   val dao=db.dao();val raw="a".repeat(HealthMetadataChunks.SIZE+10)
   dao.meta(Metadata(HealthImport.KEY,raw));val first=dao.healthReadSource(null)
   assertEquals(raw,first.raw);assertFalse(first.unchanged)
   assertTrue(dao.healthReadSource(first.token).unchanged);assertNull(dao.healthReadSource(first.token).raw)
   val correction="b".repeat(raw.length);dao.meta(Metadata(HealthImport.KEY,correction));val changed=dao.healthReadSource(first.token)
   assertFalse(changed.unchanged);assertNotEquals(first.token,changed.token);assertEquals(correction,changed.raw)
   val legacy=JSONObject(changed.token!!).apply{remove("sha256")}.toString();dao.putRawMeta(Metadata(HealthImport.KEY,legacy))
   assertFalse(dao.healthReadSource(legacy).unchanged);assertEquals(correction,dao.healthReadSource(legacy).raw)
  } finally {db.close()}
 }
}
