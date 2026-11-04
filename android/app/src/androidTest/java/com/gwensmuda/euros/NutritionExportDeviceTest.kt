package com.gwensmuda.euros

import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.NutritionRecord
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.time.OffsetDateTime

/** Emulator only: isolated Room DB and one test-owned Health Connect record, removed in finally. */
@RunWith(AndroidJUnit4::class)
class NutritionExportDeviceTest {
 @Test fun foodExportRetriesEditsAndDeletesOneHealthConnectRecord()=runBlocking {
  check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")){"Synthetic export test is emulator-only"}
  val context=ApplicationProvider.getApplicationContext<android.content.Context>()
  val client=HealthConnectClient.getOrCreate(context)
  assertTrue(NutritionExport.permission in client.permissionController.getGrantedPermissions())
  val db=Room.inMemoryDatabaseBuilder(context,EurosDatabase::class.java).build()
  val id="emulator-export-${java.util.UUID.randomUUID()}"
  val payload=JSONObject().put("consumed_at",OffsetDateTime.now().minusMinutes(1).toString()).put("raw_text","Synthetic export verification").put("nutrients",JSONObject().put("kcal",123).put("protein_g",12).put("carbs_g",JSONObject.NULL).put("fat_g",0))
  var food=Record("food_entries",id,1,payload.toString())
  try {
   val healthId=client.insertRecords(listOf(NutritionExport.record(food,0))).recordIdsList.single()
   db.dao().put(food)
   assertTrue(NutritionExport.sync(context,db.dao()))
   assertEquals(0L,client.readRecord(NutritionRecord::class,healthId).record.metadata.clientRecordVersion)
   db.dao().meta(Metadata(NutritionExport.ENABLED,"true"))
   assertTrue(NutritionExport.sync(context,db.dao()))
   val first=client.readRecord(NutritionRecord::class,healthId).record
   assertEquals(123.0,first.energy!!.inKilocalories,0.0);assertNull(first.totalCarbohydrate)
   val version=first.metadata.clientRecordVersion
   // Simulate the process dying after provider success but before local acknowledgement.
   val key="nutrition_export:$id"
   db.dao().meta(Metadata(key,JSONObject(db.dao().meta(key)!!).put("acknowledged",false).toString()))
   assertTrue(NutritionExport.sync(context,db.dao()))
   assertEquals(version,client.readRecord(NutritionRecord::class,healthId).record.metadata.clientRecordVersion)
   payload.getJSONObject("nutrients").put("kcal",456)
   food=food.copy(payload=payload.toString());db.dao().put(food)
   assertTrue(NutritionExport.sync(context,db.dao()))
   val edited=client.readRecord(NutritionRecord::class,healthId).record
   assertEquals(456.0,edited.energy!!.inKilocalories,0.0)
   assertTrue(edited.metadata.clientRecordVersion>version)
   db.dao().put(food.copy(deleted=true));assertTrue(NutritionExport.sync(context,db.dao()))
   assertTrue(runCatching{client.readRecord(NutritionRecord::class,healthId)}.isFailure)
  } finally {
   client.deleteRecords(NutritionRecord::class,emptyList(),listOf(NutritionExport.clientId(id)))
   db.close()
  }
 }
}
