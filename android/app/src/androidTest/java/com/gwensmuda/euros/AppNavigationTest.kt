package com.gwensmuda.euros

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Full root navigation against an isolated in-memory store. Emulator only. */
@RunWith(AndroidJUnit4::class)
class AppNavigationTest {
 @get:Rule val compose=createComposeRule()
 private lateinit var db:EurosDatabase
 private lateinit var repo:Repository
 private lateinit var restoration:StateRestorationTester
 @Before fun setup() {
  db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  repo=Repository(db)
  restoration=StateRestorationTester(compose)
  restoration.setContent {EurosTheme {Euros(repo)}}
  compose.waitUntil(10_000){runBlocking{repo.dao.pending().isNotEmpty()}}
 }
 @After fun close(){db.close()}
 private fun tab(name:String)=compose.onNode(hasText(name) and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected))
 private fun waitForDescription(label:String) {
  compose.waitUntil(5_000){compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty()}
 }
 @Test fun foodCloseReturnsToFoodAndReopensTheSameUnsavedDraft() {
  tab("Food").performClick();tab("Food").assertIsSelected()
  compose.onNodeWithContentDescription("Log food").performScrollTo().performClick()
  compose.waitUntil(5_000){compose.onAllNodesWithText("What did you eat?").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("What did you eat?").performTextInput("Synthetic navigation draft")
  compose.onNodeWithContentDescription("Close").performScrollTo().performClick()
  waitForDescription("Log food")
  tab("Food").assertIsSelected();tab("Today").assertIsNotSelected()
  compose.onNodeWithText("Keep eating").assertExists()
  compose.onNodeWithContentDescription("Log food").performScrollTo().performClick()
  compose.waitUntil(5_000){compose.onAllNodesWithText("Synthetic navigation draft").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("Synthetic navigation draft").assertExists()
  org.junit.Assert.assertTrue(runBlocking{repo.dao.recordsIncludingDeleted().none{it.type=="food_entries"}})
 }
 @Test fun weekDaysExpandAndCollapseWithoutLeavingWeek() {
  tab("Week").performClick();tab("Week").assertIsSelected()
  val weekday=LocalDate.now(PlanDomain.zone).dayOfWeek.name
  compose.onNodeWithContentDescription("Collapse $weekday").performScrollTo().performClick()
  compose.onNodeWithContentDescription("Expand $weekday").assertExists()
  compose.onNodeWithContentDescription("Expand $weekday").performScrollTo().performClick()
  compose.onNodeWithContentDescription("Collapse $weekday").assertExists()
  tab("Week").assertIsSelected()
  compose.onNodeWithContentDescription("Next week").performScrollTo().performClick()
  compose.onNodeWithText("This week").performScrollTo().performClick()
  tab("Week").assertIsSelected()
 }
 @Test fun settingsSectionsReturnToSettingsAndClosePreservesFoodTab() {
  tab("Food").performClick()
  compose.onNodeWithContentDescription("Settings").performClick()
  compose.onNodeWithText("Notifications").performScrollTo().performClick()
  compose.onNodeWithText("Quiet from").assertExists()
  compose.onNodeWithContentDescription("Back to settings").performScrollTo().performClick()
  compose.onNodeWithText("Data").performScrollTo().performClick()
  compose.onNodeWithText("Export data").assertExists()
  compose.onNodeWithText("Restore records").assertExists()
  compose.onNodeWithContentDescription("Back to settings").performScrollTo().performClick()
  compose.onNodeWithContentDescription("Close settings").performScrollTo().performClick()
  waitForDescription("Log food")
  tab("Food").assertIsSelected();tab("Today").assertIsNotSelected()
 }
 @Test fun selectedTabSurvivesSavedStateRestoration() {
  tab("Food").performClick();tab("Food").assertIsSelected()
  restoration.emulateSavedInstanceStateRestore()
  waitForDescription("Log food")
  tab("Food").assertIsSelected()
  tab("Week").performClick()
  restoration.emulateSavedInstanceStateRestore()
  tab("Week").assertIsSelected()
 }
}
