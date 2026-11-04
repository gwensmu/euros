package com.gwensmuda.euros

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.time.ZoneId

/** Emulator only: real device zone, isolated database, no personal records or network writes. */
@RunWith(AndroidJUnit4::class)
class TravelTimezoneScreenTest {
 @get:Rule val compose=createComposeRule()
 @Test fun scheduledLisbonTimeAndMoveDialogFollowDeviceZone() {
  val expectedZone=InstrumentationRegistry.getArguments().getString("expectedZone")!!
  val expectedTime=if(expectedZone=="Europe/Lisbon")"10:00 AM" else "4:00 AM"
  Assert.assertEquals(expectedZone,ZoneId.systemDefault().id)
  val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  try {
   val repo=Repository(db)
   val r=Record("activities","isolated-travel",1,JSONObject().put("title","Easy movement").put("kind","rest").put("status","pending").put("scheduled_at","2027-01-09T10:00:00Z").put("time_zone","Europe/Lisbon").toString())
   compose.setContent{EurosTheme{PlanRow(r,listOf(r),{},repo)}}
   compose.onNodeWithText(expectedTime).assertIsDisplayed()
   compose.onNodeWithContentDescription("Move Easy movement").performClick()
   compose.onNodeWithText("Move activity").assertIsDisplayed()
   compose.onNode(hasText(expectedTime) and hasAnyAncestor(isDialog())).assertIsDisplayed()
   compose.onNodeWithText("Cancel").performClick()
   Assert.assertEquals("2027-01-09T10:00:00Z",JSONObject(r.payload).getString("scheduled_at"))
  } finally {db.close()}
 }
}
