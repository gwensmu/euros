package com.gwensmuda.euros

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** In-memory synthetic fixture only. Run this class on an emulator, not the personal phone. */
@RunWith(AndroidJUnit4::class)
class FoodScreenTest {
 @get:Rule val compose=createComposeRule()
 private lateinit var db:EurosDatabase
 private lateinit var repo:Repository
 private val date=LocalDate.of(2026,10,14)
 @Before fun setUp() {
  db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  repo=Repository(db)
  compose.setContent {
   val records by repo.dao.observe().collectAsState(initial=emptyList())
   MaterialTheme {Column(Modifier.verticalScroll(rememberScrollState())) {FoodScreen(repo,records,date,JSONObject("""{"kcal":2000,"proteinG":130,"carbohydrateG":240,"fatG":null}"""),FoodDomain.mealReferences(JSONObject(ApplicationProvider.getApplicationContext<android.content.Context>().assets.open("euros.json").bufferedReader().use{it.readText()})))}}
  }
 }
 @After fun close(){db.close()}
 private fun open() {
  compose.onNodeWithContentDescription("Log food").performScrollTo().performClick()
  compose.waitUntil(5_000){compose.onAllNodesWithText("What did you eat?").fetchSemanticsNodes().isNotEmpty()}
 }
 private fun details() {if(compose.onAllNodesWithText("Calories · kcal").fetchSemanticsNodes().isEmpty()) compose.onNodeWithText("Details").performScrollTo().performClick()}
 private fun set(label:String,value:String) {if(label!="What did you eat?") details();compose.onNodeWithText(label).performScrollTo().performTextClearance();compose.onNodeWithText(label).performTextInput(value)}
 private fun reviewAndSave() {
  compose.onNodeWithText("Add").performScrollTo().performClick()
  compose.onNodeWithContentDescription("Save food").performScrollTo().performClick()
  compose.waitUntil(5_000){compose.onAllNodesWithContentDescription("Log food").fetchSemanticsNodes().isNotEmpty()}
 }
 @Test fun manualFoodCanBeCorrectedAndDeletedWithoutDoubleCounting() {
  open();set("What did you eat?","Synthetic meal");set("Quantity · g","100");set("Calories · kcal","120");set("Protein · g","10");set("Carbs · g","20");set("Fat · g","0")
  reviewAndSave()
  compose.onNodeWithText("120").assertExists()
  compose.onNodeWithText("Entries · 1").performScrollTo().performClick()
  compose.onNodeWithContentDescription("Edit Synthetic meal").performScrollTo().performClick()
  compose.waitUntil(5_000){compose.onAllNodesWithText("What did you eat?").fetchSemanticsNodes().isNotEmpty()}
  set("Calories · kcal","80");reviewAndSave()
  compose.onNodeWithText("80").assertExists();compose.onNodeWithText("Entries · 1").assertExists()
  compose.onNodeWithContentDescription("Edit Synthetic meal").performScrollTo().performClick()
  compose.waitUntil(5_000){compose.onAllNodesWithText("Delete entry").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Delete entry").performScrollTo().performClick();compose.onNodeWithText("Delete",useUnmergedTree=true).performClick()
  compose.waitUntil(5_000){compose.onAllNodesWithContentDescription("Log food").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Entries · 1").assertDoesNotExist()
 }
 @Test fun unfinishedDraftReopensAndAddDoesNotCommit() {
  open();set("What did you eat?","Synthetic draft");set("Calories · kcal","42")
  compose.onNodeWithContentDescription("Close").performScrollTo().performClick()
  compose.waitUntil(5_000){compose.onAllNodesWithContentDescription("Log food").fetchSemanticsNodes().isNotEmpty()}
  open();compose.onNodeWithText("Synthetic draft").assertExists();details();compose.onNodeWithText("42").assertExists()
  compose.onNodeWithText("Add").performScrollTo().performClick();compose.onNodeWithContentDescription("Save food").assertExists()
  val pending=runBlocking{repo.dao.pending()}
  org.junit.Assert.assertTrue("Review must not create consumption or outbox records",pending.isEmpty())
 }
 @Test fun dinnerIsVisibleSelectableAndSavedAsItsOwnMeal() {
  compose.onNodeWithText("Dinner").performScrollTo().assertIsDisplayed()
  open();set("What did you eat?","Synthetic dinner");set("Calories · kcal","123")
  compose.onNodeWithText("Meal").performScrollTo().performClick()
  compose.onNodeWithText("Dinner").performClick()
  reviewAndSave()
  compose.onNodeWithText("Dinner").assertDoesNotExist()
  compose.onNodeWithText("Post-training meal").performScrollTo().assertIsDisplayed()
  val saved=runBlocking{repo.dao.pending()}.single()
  org.junit.Assert.assertEquals("dinner",JSONObject(saved.payload).getString("meal_reference_id"))
 }
 @Test fun entryStartsWithTextAndAddWhileManualDetailsRemainAvailable() {
  open()
  compose.onNodeWithText("Add").assertExists()
  compose.onNodeWithText("Calories · kcal").assertDoesNotExist()
  compose.onNodeWithText("Date · YYYY-MM-DD").assertDoesNotExist()
  details()
  compose.onNodeWithText("Calories · kcal").assertExists()
  compose.onNodeWithText("Date · YYYY-MM-DD").assertExists()
  compose.onNodeWithText("Details").performScrollTo().performClick()
  compose.onNodeWithText("Calories · kcal").assertDoesNotExist()
 }
}
