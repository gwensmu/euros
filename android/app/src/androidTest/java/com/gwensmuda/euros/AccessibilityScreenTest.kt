package com.gwensmuda.euros

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** Narrow device with 200% text; fixtures never enter the application database. */
class AccessibilityScreenTest {
 @get:Rule val compose=createComposeRule()
 private fun screen(content:@androidx.compose.runtime.Composable ()->Unit) {
  compose.setContent {
   CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,2f)) {
    EurosTheme { Column(Modifier.width(320.dp).verticalScroll(rememberScrollState()).padding(24.dp)) {content()} }
   }
  }
 }
 private fun noClippedText() {
  compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),useUnmergedTree=true).fetchSemanticsNodes().forEach { node ->
   val layouts=mutableListOf<TextLayoutResult>()
   node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
   layouts.forEach { assertTrue("Clipped text: ${it.layoutInput.text} size=${it.size} paragraph=${it.multiParagraph.width} x ${it.multiParagraph.height}",(0 until it.lineCount).all { line -> it.getLineRight(line)-it.getLineLeft(line)<=it.size.width+1f } && it.multiParagraph.height<=it.size.height+1f) }
  }
 }
 @Test fun countdownAndWeightRemainReadableAtDoubleText() {
  val date=LocalDate.of(2026,11,4)
  val weights=(1..3).map { Record("weight_entries","test-$it",0,"""{"measured_at":"2026-10-0${it}T08:30:00-05:00","weight_lb":"160.$it"}""") }
  screen {PageTitle(RootTab.Today,date,date,date);WeightGraph(weights)}
  noClippedText()
  val number=compose.onNodeWithText("70",useUnmergedTree=true)
  val layouts=mutableListOf<TextLayoutResult>()
  number.performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
  assertEquals("Countdown must stay one number",1,layouts.single().lineCount)
 }
 @Test fun activityMoveRemainsReachableAtDoubleText()=runBlocking<Unit> {
  val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  try {
   val record=Record("activities","test-rest",1,"""{"title":"Down Dog gentle stretching","kind":"rest","status":"pending","scheduled_at":"2026-11-04T20:30:00-06:00"}""")
   screen {ActivityDetail(record,listOf(record),Repository(db),{},{},{})}
   noClippedText()
   compose.onNodeWithContentDescription("Move activity").performScrollTo().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
   compose.onNodeWithText("Move activity").assertExists()
  } finally {db.close()}
 }
 @Test fun unreadableWorkoutCacheShowsRecoveryActionInsteadOfCrashing()=runBlocking<Unit> {
  val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  try {
   db.dao().meta(Metadata(HealthImport.KEY,"invalid cache"))
   screen {ImportedWorkoutScreen("missing",Repository(db)) {}}
   compose.waitUntil(5_000){compose.onAllNodesWithText("Couldn't read this workout. Refresh Health Connect in Settings.").fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithContentDescription("Close").performScrollTo().assertHasClickAction()
  } finally {db.close()}
 }

}
