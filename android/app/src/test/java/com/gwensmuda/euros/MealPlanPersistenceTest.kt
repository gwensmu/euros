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
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class)
class MealPlanPersistenceTest {
 @Test fun authoredSavedPlansSyncExportAndRestoreWithoutFoodConsumption()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>()
  val source=Room.inMemoryDatabaseBuilder(context,EurosDatabase::class.java).build()
  val restored=Room.inMemoryDatabaseBuilder(context,EurosDatabase::class.java).build()
  try {
   val commands=JSONArray(java.io.File("../../plan/meal-plan-commands.json").readText())
   val repo=Repository(source)
   val api=object:SyncApi {
    override fun request(path:String,body:JSONObject?):JSONObject {
     check(body==null)
     val changes=JSONArray()
     for(i in 0 until commands.length()) {
      val c=commands.getJSONObject(i)
      changes.put(JSONObject().put("entity_type","meal_plans").put("entity_id",c.getString("entity_id")).put("revision",1).put("deleted",false).put("payload",c.getJSONObject("payload")).put("cursor",i+1))
     }
     return JSONObject().put("changes",changes).put("next_cursor",commands.length()).put("has_more",false)
    }
   }
   repo.sync(api)
   val records=repo.dao.recordsIncludingDeleted()
   assertEquals(7,records.size);assertTrue(repo.dao.pending().isEmpty())
   for(offset in 0L..6L) {
    val day=LocalDate.parse("2026-10-19").plusDays(offset)
    val saved=FoodDomain.savedMealPlan(records,day)!!
    assertEquals(if(offset%2==0L)"A" else "B",saved.getString("reference_day"))
    RecordValidation.validate("meal_plans",saved)
    assertEquals(4,DayPlanComposer.compose(JSONObject(),records,day).meals.size)
   }
   assertTrue(BackendLlmClient.weeklySnapshot(LocalDate.parse("2026-10-19"),records).isEmpty())
   val archive=ExportService(context,repo.dao).document()
   val second=Repository(restored)
   assertEquals(7,second.restoreRecords(ExportService.validateEnvelope(archive)).imported)
   assertEquals(0,second.restoreRecords(ExportService.validateEnvelope(archive)).imported)
   assertEquals(records.map{it.id to it.payload}.toSet(),second.dao.recordsIncludingDeleted().map{it.id to it.payload}.toSet())
   val payload=JSONObject(records.first().payload)
   assertTrue(runCatching{second.upsert("meal_plans","wrong-id",payload)}.isFailure)
   assertEquals(7,second.dao.pending().size)
  }finally{source.close();restored.close()}
 }
}
