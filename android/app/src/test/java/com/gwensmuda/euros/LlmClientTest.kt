package com.gwensmuda.euros

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=android.app.Application::class)
class LlmClientTest {
    private val week=LocalDate.parse("2026-10-12")
    private fun food(id:String,day:String,revision:Long=0,deleted:Boolean=false)=Record(
        "food_entries",id,revision,JSONObject().put("local_date",day).put("raw_text","Eggs").toString(),deleted)
    private fun weight(id:String,day:String,anchor:String)=Record("weight_entries",id,0,
        JSONObject().put("local_date",day).put("weekly_anchor",anchor).toString())

    @Test fun weeklyRequestIncludesUnsyncedRecordsButNotOtherWeeksOrDeletedRecords()=runBlocking {
        val source=listOf(food("current","2026-10-14"), food("old","2026-10-01"),
            food("deleted","2026-10-14"),food("deleted","2026-10-14",1,true),
            weight("prior","2026-10-11","2026-10-05"))
        var sent:JSONObject?=null
        val client=BackendLlmClient(object:SyncApi {
            override fun request(path:String,body:JSONObject?):JSONObject {
                assertEquals("/v1/review/week",path);sent=body;return JSONObject()
            }
        })
        client.reviewWeek(week,source)
        val records=sent!!.getJSONArray("records")
        assertEquals(2,records.length())
        assertEquals(setOf("current","prior"),(0 until records.length()).map{records.getJSONObject(it).getString("entity_id")}.toSet())
        assertEquals(0L,records.getJSONObject(0).getLong("revision"))
        assertEquals("2026-10-12",sent!!.getString("week_start"))
    }

    @Test fun crossWeekCompletionAndRecoveryFamilyAreIncluded() {
        val parent=Record("activities","parent",0,"""{"scheduled_at":"2026-10-11T23:55:00-05:00","kind":"rest","status":"pending"}""")
        val step=Record("activities","step",0,"""{"scheduled_at":"2026-10-12T00:05:00-05:00","parent_id":"parent","kind":"recovery","status":"done","completed_at":"2026-10-12T00:10:00-05:00"}""")
        val late=Record("activities","late",0,"""{"scheduled_at":"2026-10-11T11:00:00-05:00","kind":"weights","status":"done","completed_at":"2026-10-12T11:00:00-05:00"}""")
        assertEquals(setOf("parent","step","late"),BackendLlmClient.weeklySnapshot(week,listOf(parent,step,late)).map{it.id}.toSet())
    }

    @Test fun foodInterpretationSendsOnlyTextAndDoesNotWriteRecords()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
        try {
            val client=BackendLlmClient(object:SyncApi {
                override fun request(path:String,body:JSONObject?):JSONObject {
                    assertEquals("/v1/interpret/food",path)
                    assertEquals(setOf("text"),body!!.keys().asSequence().toSet())
                    assertEquals("3 eggs",body.getString("text"))
                    return JSONObject().put("raw_text","3 eggs")
                }
            })
            val repo=Repository(db,client)
            assertEquals("3 eggs",repo.interpretFood("3 eggs").getString("raw_text"))
            assertTrue(db.dao().recordsIncludingDeleted().isEmpty())
            assertTrue(db.dao().pending().isEmpty())
        } finally {db.close()}
    }

    @Test fun connectionIsSessionOnlyAndNeverExported()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
        try {
            val repo=Repository(db)
            assertTrue(runCatching{repo.interpretFood("Eggs")}.isFailure)
            val api=object:SyncApi {
                override fun request(path:String,body:JSONObject?):JSONObject =
                    if(path.startsWith("/v1/changes")) JSONObject("""{"changes":[],"next_cursor":0,"has_more":false}""")
                    else JSONObject().put("raw_text","Eggs")
            }
            repo.sync(api)
            assertEquals("Eggs",repo.interpretFood("Eggs").getString("raw_text"))
            assertEquals(setOf("server_identity","cursor"),db.dao().allMetadata().map{it.key}.toSet())
            assertTrue(runCatching{Repository(db).interpretFood("Eggs")}.isFailure)
            repo.disconnectInterpretation()
            assertTrue(runCatching{repo.interpretFood("Eggs")}.isFailure)
        } finally {db.close()}
    }

    @Test fun failedAuthenticationDoesNotEnableInterpretationOrBindServer()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
        try {
            val repo=Repository(db)
            val api=object:SyncApi {override fun request(path:String,body:JSONObject?):JSONObject=error("Unauthorized")}
            assertTrue(runCatching{repo.sync(api)}.isFailure)
            assertNull(db.dao().meta("server_identity"))
            assertTrue(runCatching{repo.interpretFood("Eggs")}.isFailure)
        } finally {db.close()}
    }

    @Test fun backendErrorsAreActionableWithoutExposingRawProviderDetails() {
        assertTrue(backendErrorMessage(503,"{\"error\":\"model_not_configured\"}").contains("configured"))
        assertTrue(backendErrorMessage(503,"{\"error\":\"model_busy\"}").contains("Try again"))
        assertFalse(backendErrorMessage(502,"secret provider detail").contains("secret"))
        assertFalse(backendErrorMessage(503,"{\"error\":\"model_busy\"}").contains("configured"))
    }

    @Test fun invalidInputDoesNotReachServer()=runBlocking {
        val client=BackendLlmClient(object:SyncApi {override fun request(path:String,body:JSONObject?):JSONObject=throw AssertionError("Unexpected request")})
        assertTrue(runCatching{client.interpretFood(" ")}.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching{client.interpretFood("x".repeat(8001))}.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching{client.reviewWeek(week.plusDays(1),emptyList())}.exceptionOrNull() is IllegalArgumentException)
    }
}
