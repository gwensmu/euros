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
        assertTrue(backendErrorMessage(429,"{\"error\":\"model_hourly_limit\"}").contains("Hourly"))
        assertTrue(backendErrorMessage(429,"{\"error\":\"model_budget_exhausted\"}").contains("monthly"))
        assertTrue(backendErrorMessage(429,"{\"error\":\"model_account_budget_exhausted\"}").contains("Your monthly"))
    }

    @Test fun invalidInputDoesNotReachServer()=runBlocking {
        val client=BackendLlmClient(object:SyncApi {override fun request(path:String,body:JSONObject?):JSONObject=throw AssertionError("Unexpected request")})
        assertTrue(runCatching{client.interpretFood(" ")}.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching{client.interpretFood("x".repeat(8001))}.exceptionOrNull() is IllegalArgumentException)
    }
}
