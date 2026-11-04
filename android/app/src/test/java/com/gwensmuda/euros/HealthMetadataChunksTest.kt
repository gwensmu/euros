package com.gwensmuda.euros

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class)
class HealthMetadataChunksTest {
 @Test fun encodingRejectsSnapshotsThatItsReaderCannotOpen() {
  val state=JSONObject().put("data","x".repeat(1000))
  assertTrue(runCatching{HealthSnapshotStorage.encode(state,maxDecodedBytes=100)}.isFailure)
  assertEquals(state.toString(),HealthSnapshotStorage.decode(HealthSnapshotStorage.encode(state)).toString())
 }
 @Test fun largeSnapshotRoundTripsThroughReadObserveExportAndRemoval()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  try {
   val dao=db.dao();val large="x".repeat(1_700_000)
   dao.meta(Metadata(HealthImport.KEY,large))
   assertTrue(dao.rawMetadata().all{it.value.length<=HealthMetadataChunks.SIZE})
   assertEquals(large,dao.meta(HealthImport.KEY))
   assertEquals(large,dao.observeMeta(HealthImport.KEY).first())
   assertEquals(large,dao.exportSnapshot().metadata.single().value)
   assertEquals(5,dao.rawMetadata().size)
   dao.meta(Metadata(HealthImport.KEY,"legacy-small"))
   assertEquals(1,dao.rawMetadata().size)
   assertEquals("legacy-small",dao.meta(HealthImport.KEY))
   dao.meta(Metadata(HealthImport.KEY,large));dao.removeMeta(HealthImport.KEY)
   assertNull(dao.meta(HealthImport.KEY));assertTrue(dao.rawMetadata().isEmpty())
  }finally{db.close()}
 }
 @Test fun legacyReadsAndRejectedReplacementPreservePreviousSnapshot()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  try {
   val dao=db.dao();dao.putRawMeta(Metadata(HealthImport.KEY,"legacy"))
   assertEquals("legacy",dao.meta(HealthImport.KEY))
   assertTrue(runCatching{dao.meta(Metadata(HealthImport.KEY,"x".repeat(16_000_001)))}.isFailure)
   assertEquals("legacy",dao.meta(HealthImport.KEY))
   val header=JSONObject().put("encoding","metadata-chunks-v1").put("count",2).put("length",10).toString()
   assertTrue(runCatching{HealthMetadataChunks.assemble(header){null}}.isFailure)
  }finally{db.close()}
 }
}
