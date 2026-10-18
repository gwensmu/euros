package com.gwensmuda.euros

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.time.LocalDate

/** Emulator-only synthetic provider and in-memory database; no network or personal data. */
@RunWith(AndroidJUnit4::class)
class WeeklyReviewScreenTest {
 @get:Rule val compose=createComposeRule()
 private lateinit var db:EurosDatabase
 private lateinit var repo:Repository
 private var week by mutableStateOf(LocalDate.of(2026,10,12))
 private var calls=0
 private var fail=false
 private fun response()=JSONObject("""{"summary":"Two sessions completed.","adjustments":["Keep the scheduled recovery."],"coverage":{"through_date":"2026-10-14","logged_activity_count":2,"unlogged_activity_count":1,"food_days":2,"covered_days":3},"model":"synthetic","provider":"test"}""")
 @Before fun setup() {
  db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  repo=Repository(db,object:LlmClient {
   override suspend fun interpretFood(text:String):JSONObject=error("Not used")
   override suspend fun reviewWeek(weekStart:LocalDate,records:List<Record>):JSONObject {
    calls++;if(fail)throw IllegalStateException("Synthetic provider failure")
    return response()
   }
  })
  compose.setContent {
   val records by repo.dao.observe().collectAsState(emptyList())
   var open by remember { mutableStateOf(true) }
   EurosTheme {Column(Modifier.verticalScroll(rememberScrollState())) {
    if(open) WeeklyReview(repo,records,week){open=false}
    else TextButton(onClick={open=true}){Text("Open wrap-up")}
   }}
  }
 }
 @After fun close(){db.close()}
 private fun waitFor(text:String) {compose.waitUntil(5_000){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}
 private fun generate() {waitFor("Generate wrap-up");compose.onNodeWithText("Generate wrap-up").performScrollTo().performClick()}
 private fun assertReadOnly()=runBlocking {
  Assert.assertTrue(repo.dao.recordsIncludingDeleted().isEmpty())
  Assert.assertTrue(repo.dao.pending().isEmpty())
 }
 @Test fun generationIsReadOnlyAndReopenUsesSavedSnapshot() {
  generate();waitFor("Two sessions completed.")
  compose.onNodeWithText("Keep the scheduled recovery.").assertExists()
  compose.onNodeWithText("1 activities without a complete log · 2 of 3 days with food entries").assertExists()
  Assert.assertEquals(1,calls);assertReadOnly()
  val cache=runBlocking{repo.dao.meta(WeeklyReviewCache.key(week))}!!
  Assert.assertTrue(JSONObject(cache).getString("generated_at").isNotBlank())
  compose.onNodeWithContentDescription("Close").performScrollTo().performClick()
  compose.onNodeWithText("Open wrap-up").performScrollTo().performClick()
  waitFor("Two sessions completed.")
  Assert.assertEquals(1,calls);assertReadOnly()
 }
 @Test fun providerFailureOffersRetryWithoutInventingSummary() {
  fail=true;generate();waitFor("Retry")
  compose.onNodeWithText("Two sessions completed.").assertDoesNotExist()
  Assert.assertNull(runBlocking{repo.dao.meta(WeeklyReviewCache.key(week))})
  assertReadOnly();fail=false
  compose.onNodeWithText("Retry").performScrollTo().performClick()
  waitFor("Two sessions completed.")
  Assert.assertEquals(2,calls);assertReadOnly()
 }
 @Test fun failedRefreshPreservesValidSummaryAndCache() {
  generate();waitFor("Two sessions completed.")
  val saved=runBlocking{repo.dao.meta(WeeklyReviewCache.key(week))}
  fail=true
  compose.onNodeWithText("Refresh wrap-up").performScrollTo().performClick()
  waitFor("Retry")
  compose.onNodeWithText("Two sessions completed.").assertExists()
  Assert.assertEquals(saved,runBlocking{repo.dao.meta(WeeklyReviewCache.key(week))})
  Assert.assertEquals(2,calls);assertReadOnly()
 }
 @Test fun futureWeekShowsEmptyStateWithoutProviderCall() {
  compose.runOnIdle {week=LocalDate.now(PlanDomain.zone).plusWeeks(2)}
  waitFor("No logs for this week yet")
  compose.onNodeWithText("Generate wrap-up").assertDoesNotExist()
  Assert.assertEquals(0,calls);assertReadOnly()
 }

}
