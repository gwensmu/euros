package com.gwensmuda.euros

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StrengthFeedbackTest {
 @get:Rule val compose=createComposeRule()
 private lateinit var db:EurosDatabase
 private lateinit var repo:Repository
 private val id="synthetic-strength-feedback"
 @Before fun setup()=runBlocking {
  db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  repo=Repository(db)
  repo.upsert("activities",id,JSONObject().put("kind","weights").put("title","Full-body weights").put("status","pending").put("scheduled_at","2026-11-08T11:00:00-06:00"))
 }
 @After fun close(){db.close()}
 private fun record()=runBlocking{repo.dao.record("activities",id)!!}
 private fun feedback()=JSONObject(record().payload).getJSONObject("strength_feedback")
 private fun complete()=runBlocking{repo.complete(record(),"done")}
 @Test fun moveDialogCancelActivitySkipsWithoutSavingDraftTime() {
  val before=record();var closed=false
  compose.setContent{EurosTheme{MoveDialog(before,repo){closed=true}}}
  compose.onNodeWithText("Tomorrow").performClick()
  compose.onNodeWithText("Cancel activity").performClick()
  compose.waitUntil(5_000){closed}
  val saved=JSONObject(record().payload)
  assertEquals("skipped",saved.getString("status"))
  assertEquals(JSONObject(before.payload).getString("scheduled_at"),saved.getString("scheduled_at"))
  assertTrue(saved.has("skipped_at"))
  assertFalse(saved.has("strength_feedback"))
 }
 @Test fun moveDialogCloseDoesNotChangeActivity() {
  val before=record();val queued=runBlocking{repo.dao.pending().size};var closed=false
  compose.setContent{EurosTheme{MoveDialog(before,repo){closed=true}}}
  compose.onNodeWithText("Tomorrow").performClick()
  compose.onNodeWithContentDescription("Close").performClick()
  compose.runOnIdle{assertTrue(closed)}
  assertEquals(before,record())
  assertEquals(queued,runBlocking{repo.dao.pending().size})
 }
 @Test fun backRetainsDurableDefaultWithoutAnotherWrite() {
  complete();val count=runBlocking{repo.dao.pending().size};var closed=false
  compose.setContent{EurosTheme{Column{StrengthFeedbackScreen(record(),repo){closed=true}}}}
  compose.onNodeWithText("How did it feel?").assertExists()
  compose.onNodeWithText("Workout done").assertDoesNotExist()
  compose.onNodeWithText("Not now").assertDoesNotExist()
  compose.onNodeWithText("Anything in particular?").assertDoesNotExist()
  compose.onNodeWithContentDescription("Back").performClick()
  compose.runOnIdle{assertTrue(closed)}
  assertEquals("about_right",feedback().getString("feeling"));assertEquals("default",feedback().getString("source"))
  assertEquals(count,runBlocking{repo.dao.pending().size})
 }
 @Test fun choicePersistsAndRepeatedChoiceDoesNotDuplicateCommand() {
  complete();var closed=false;val saved=record()
  compose.setContent{EurosTheme{Column{StrengthFeedbackScreen(saved,repo){closed=true}}}}
  compose.onNodeWithText("Too hard").performClick()
  compose.waitUntil(5_000){closed}
  assertEquals("too_hard",feedback().getString("feeling"));assertEquals("selected",feedback().getString("source"))
  val count=runBlocking{repo.dao.pending().size}
  runBlocking{repo.saveStrengthFeedback(record(),StrengthFeeling.TOO_HARD)}
  assertEquals(count,runBlocking{repo.dao.pending().size})
  assertEquals(JSONObject(saved.payload).getString("completed_at"),JSONObject(record().payload).getString("completed_at"))
 }
 @Test fun moveIsPreservedAndSkipClearsFeedback()=runBlocking {
  complete();val before=record()
  repo.move(before,"2026-11-09T11:00:00-06:00")
  repo.saveStrengthFeedback(before,StrengthFeeling.EASY)
  assertEquals("2026-11-09T11:00:00-06:00",JSONObject(record().payload).getString("scheduled_at"))
  repo.complete(record(),"skipped")
  assertFalse(JSONObject(record().payload).has("strength_feedback"))
  assertTrue(runCatching{repo.saveStrengthFeedback(before,StrengthFeeling.EASY)}.isFailure)
 }
 @Test fun nextPrescriptionAndFeedbackCommitTogetherAndUndoRevertsThePendingPlan()=runBlocking {
  val original=JSONObject(record().payload)
  val prescription=JSONObject().put("exercises",org.json.JSONArray().put(JSONObject().put("name","Row").put("sets",3).put("reps",10).put("loadLb",25)))
  original.put("prescription",prescription)
  repo.upsert("activities",id,original)
  repo.upsert("activities","next-strength",JSONObject(original.toString()).put("scheduled_at","2026-11-15T11:00:00-06:00"))
  complete();repo.saveStrengthFeedback(record(),StrengthFeeling.EASY)
  val next=repo.dao.record("activities","next-strength")!!
  assertEquals(11,StrengthProgression.template(JSONObject(next.payload)).getJSONArray("exercises").getJSONObject(0).getInt("reps"))
  assertTrue(repo.dao.pendingForEntity("activities","next-strength").isNotEmpty())
  val undone=JSONObject(record().payload).put("status","pending").apply{remove("completed_at");remove("strength_feedback")}
  repo.upsert("activities",id,undone)
  assertFalse(JSONObject(repo.dao.record("activities","next-strength")!!.payload).has(StrengthProgression.KEY))
 }
 @Test fun largeTextHasReachableChoicesAndSystemBackRetainsDefault() {
  complete();var closed=false
  var dispatcher:androidx.activity.OnBackPressedDispatcher?=null
  compose.setContent{
   dispatcher=androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
   EurosTheme{androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f,2f)) {
    Column(androidx.compose.ui.Modifier.width(320.dp)) {StrengthFeedbackScreen(record(),repo){closed=true}}
   }}
  }
  compose.onNodeWithText("Easy").assertIsDisplayed();compose.onNodeWithText("About right").assertIsDisplayed();compose.onNodeWithText("Too hard").assertIsDisplayed()
  if(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("captureStrengthFeedback")=="true") {
   val bitmap=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
   val context=ApplicationProvider.getApplicationContext<android.content.Context>()
   java.io.File(context.getExternalFilesDir(null),"strength-feedback-large.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
  }
  compose.runOnIdle{dispatcher!!.onBackPressed()}
  compose.runOnIdle{assertTrue(closed)}
  assertEquals("about_right",feedback().getString("feeling"))
 }
 @Test fun completionRetryPreservesSelectedFeedbackAndDoesNotInventMeasurements()=runBlocking {
  complete();repo.saveStrengthFeedback(record(),StrengthFeeling.EASY)
  val count=repo.dao.pending().size
  repo.complete(record(),"done")
  assertEquals(count,repo.dao.pending().size);assertEquals("easy",feedback().getString("feeling"))
  val p=JSONObject(record().payload)
  listOf("duration","duration_minutes","rpe","loadLb","reps").forEach{assertFalse(p.has(it))}
 }
}
