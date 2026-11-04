package com.gwensmuda.euros

import androidx.compose.foundation.layout.Column
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
 @Test fun completionRetryPreservesSelectedFeedbackAndDoesNotInventMeasurements()=runBlocking {
  complete();repo.saveStrengthFeedback(record(),StrengthFeeling.EASY)
  val count=repo.dao.pending().size
  repo.complete(record(),"done")
  assertEquals(count,repo.dao.pending().size);assertEquals("easy",feedback().getString("feeling"))
  val p=JSONObject(record().payload)
  listOf("duration","duration_minutes","rpe","loadLb","reps").forEach{assertFalse(p.has(it))}
 }
}
