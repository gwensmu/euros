package com.gwensmuda.euros

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated preview and root navigation; never uses the connected production store. */
@RunWith(AndroidJUnit4::class)
class MascotPoseScreenTest {
 @get:Rule val compose=createComposeRule()
 @Test fun sixActionSpritesHaveAccessibleLabelsAndFitTheirCells() {
  val poses=listOf(MascotPose.SNACK,MascotPose.AFTER_BJJ,MascotPose.LIFT,MascotPose.WATER,MascotPose.BRACKET,MascotPose.SLEEP)
  compose.setContent{EurosTheme{Column(Modifier.fillMaxWidth()){
   poses.chunked(3).forEach{row->Row(Modifier.fillMaxWidth()){row.forEach{pose->Weasel(pose,Modifier.weight(1f).height(150.dp))}}}
  }}}
  for(label in listOf("eating a snack","wiping sweat after BJJ","lifting a dumbbell","drinking water","studying a competition bracket","asleep with its tail curled around it"))
   compose.onNodeWithContentDescription("Blue-belt weasel $label").assertIsDisplayed()
  val context=ApplicationProvider.getApplicationContext<android.content.Context>()
  context.openFileOutput("mascot-action-preview.png",android.content.Context.MODE_PRIVATE).use{stream->
   compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream)
  }
 }
 @Test fun savedFoodWaitsForTodayThenShowsSnack() {
  val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  try {
   val repo=Repository(db)
   compose.setContent{EurosTheme{Euros(repo)}}
   compose.waitUntil(10_000){runBlocking{repo.dao.pending().isNotEmpty()}}
   fun tab(name:String)=compose.onNode(hasText(name) and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Selected))
   tab("Food").performClick()
   runBlocking{repo.upsert("food_entries","synthetic-mascot-food",JSONObject("""{"consumed_at":"2026-10-14T12:00:00-05:00","local_date":"2026-10-14","raw_text":"synthetic food","nutrients":{"kcal":null,"protein_g":null,"carbs_g":null,"fat_g":null}}"""))}
   compose.waitForIdle()
   tab("Today").performClick()
   compose.waitUntil(5_000){compose.onAllNodesWithContentDescription("Blue-belt weasel eating a snack").fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithContentDescription("Blue-belt weasel eating a snack").assertIsDisplayed()
  } finally {db.close()}
 }
}
