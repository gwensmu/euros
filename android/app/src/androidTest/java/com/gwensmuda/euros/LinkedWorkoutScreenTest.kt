package com.gwensmuda.euros

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Isolated store: matching persists without changing the authored activity. */
@RunWith(AndroidJUnit4::class)
class LinkedWorkoutScreenTest {
 @get:Rule val compose=createComposeRule()
 @Test fun matchedRowShowsRecordedDurationAndOpensMeasuredRecap()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<android.content.Context>()
  val db=Room.inMemoryDatabaseBuilder(context,EurosDatabase::class.java).build()
  try {
   val activity=Record("activities","test-class",1,"""{"title":"BJJ practice","kind":"bjj","status":"done","scheduled_at":"2026-11-03T18:30:00-06:00"}""")
   db.dao().put(activity)
   val exercise=JSONObject().put("kind","exercise").put("source",HealthSources.POLAR).put("source_id","test-source").put("exercise_type",44).put("start","2026-11-04T00:40:00Z").put("end","2026-11-04T01:40:00Z")
   val snapshot=JSONObject().put("schema",1).put("records",JSONObject().put("test-source",exercise)).put("links",JSONObject())
   db.dao().meta(Metadata(HealthImport.KEY,HealthSnapshotStorage.encode(snapshot)))
   val importer=HealthImport(context,db.dao());importer.reconcileLinks()
   assertEquals(mapOf("test-class" to "test-source"),importer.linkedActivities())
   assertEquals(activity,db.dao().record("activities",activity.id))
   val projection=WeekTrackerCache.projectAll(db.dao().meta(HealthImport.KEY))
   assertTrue(projection.unlinked.isEmpty())
   var opened=false
   compose.setContent{EurosTheme{PlanRow(activity,listOf(activity),{opened=true},linkedWorkout=projection.linked[activity.id])}}
   compose.onNodeWithText("60 min recorded").assertIsDisplayed()
   compose.onNodeWithContentDescription("Linked Health Connect workout").assertExists()
   compose.onNodeWithText("BJJ practice").performClick()
   assertTrue(opened)
   assertEquals(3600,java.time.Duration.between(importer.recap(activity.id)!!.session.start,importer.recap(activity.id)!!.session.end).seconds.toInt())
   importer.unlink(activity.id);importer.reconcileLinks()
   assertTrue(importer.linkedActivities().isEmpty())
   assertEquals(1,WeekTrackerCache.project(db.dao().meta(HealthImport.KEY)).size)
   importer.link(activity.id,"test-source")
   db.dao().put(activity.copy(deleted=true));importer.reconcileLinks()
   assertTrue(importer.linkedActivities().isEmpty())
   assertEquals(1,WeekTrackerCache.project(db.dao().meta(HealthImport.KEY)).size)
  }finally{db.close()}
 }
}
