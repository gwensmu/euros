package com.gwensmuda.euros

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.time.LocalDate

/** Synthetic responses only; never contacts a model or writes the personal database. */
@RunWith(AndroidJUnit4::class)
class FoodInterpretationTest {
 @get:Rule val compose=createComposeRule()
 private lateinit var db:EurosDatabase
 private lateinit var repo:Repository
 private var calls=0
 private var responder:suspend (String)->JSONObject={JSONObject("""{"raw_text":"synthetic","quantity_g":100,"nutrients":{"kcal":123,"protein_g":10,"carbs_g":20,"fat_g":0},"interpretation":{"provider":"test","model":"test"}}""")}
 @Before fun setup() {
  db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  repo=Repository(db,object:LlmClient {
   override suspend fun interpretFood(text:String):JSONObject {calls++;return responder(text)}
   override suspend fun reviewWeek(weekStart:LocalDate,records:List<Record>):JSONObject=error("Not used")
  })
  compose.setContent {val records by repo.dao.observe().collectAsState(emptyList());EurosTheme {Column(Modifier.verticalScroll(rememberScrollState())) {FoodScreen(repo,records,LocalDate.of(2026,10,14),null,emptyList())}}}
 }
 @After fun close(){db.close()}
 private fun open() {
  compose.onNodeWithContentDescription("Log food").performScrollTo().performClick()
  compose.waitUntil(5_000){compose.onAllNodesWithText("What did you eat?").fetchSemanticsNodes().isNotEmpty()}
 }
 private fun textAndAdd(){open();compose.onNodeWithText("What did you eat?").performTextInput("Synthetic rice 100g");compose.onNodeWithText("Add").performScrollTo().performClick()}
 @Test fun textInterpretationRequiresReviewBeforeConsumptionIsSaved() {
  textAndAdd()
  compose.waitUntil(5_000){compose.onAllNodesWithContentDescription("Save food").fetchSemanticsNodes().isNotEmpty()}
  Assert.assertEquals(1,calls);Assert.assertTrue(runBlocking{repo.dao.pending().isEmpty()})
  compose.onNodeWithText("123 kcal").assertExists()
  compose.onNodeWithContentDescription("Save food").performScrollTo().performClick()
  compose.waitUntil(5_000){compose.onAllNodesWithContentDescription("Log food").fetchSemanticsNodes().isNotEmpty()}
  val payload=JSONObject(runBlocking{repo.dao.pending().single().payload})
  Assert.assertEquals("Synthetic rice 100g",payload.getString("raw_text"))
  Assert.assertEquals("test",payload.getJSONObject("interpretation").getString("model"))
 }
 @Test fun failurePreservesTextAndManualFallbackCanReviewUnknownNutrients() {
  responder={throw IllegalStateException("Service unavailable")}
  textAndAdd()
  compose.waitUntil(5_000){compose.onAllNodesWithText("Service unavailable").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Synthetic rice 100g").assertExists()
  compose.onNodeWithText("Details").performScrollTo().performClick()
  compose.onNodeWithText("Use manual details").performScrollTo().performClick()
  compose.onNodeWithText("Add").performScrollTo().performClick()
  compose.onNodeWithContentDescription("Save food").assertExists();Assert.assertEquals(1,calls)
 }
 @Test fun closingPendingInterpretationDoesNotCreateFoodOrLoseDraft() {
  val response=CompletableDeferred<JSONObject>();responder={response.await()}
  textAndAdd()
  compose.waitUntil(5_000){compose.onAllNodesWithText("Adding…").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithContentDescription("Close").performScrollTo().performClick()
  compose.waitUntil(5_000){compose.onAllNodesWithContentDescription("Log food").fetchSemanticsNodes().isNotEmpty()}
  response.complete(JSONObject())
  open();compose.onNodeWithText("Synthetic rice 100g").assertExists()
  Assert.assertTrue(runBlocking{repo.dao.pending().isEmpty()})
 }
}
