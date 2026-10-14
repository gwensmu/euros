package com.gwensmuda.euros
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ReminderPersistenceTest {
 private fun activity(id:String="a",status:String="pending",seconds:Long=3600)=Record("activities",id,1,JSONObject().put("title","BJJ").put("scheduled_at",Instant.now().plusSeconds(seconds).atOffset(ZoneOffset.UTC).toString()).put("status",status).toString())
 @Test fun moveReplacesOneAlarmAndCompletionClearsDesiredState()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("euros.db");val db=EurosDatabase.open(context)
  try {
   val original=activity();db.dao().put(original);val scheduler=ReminderScheduler(context,db.dao());scheduler.saveSettings(ReminderSettings(true));scheduler.reconcile(listOf(original));scheduler.reconcile(listOf(original))
   assertEquals(1,JSONObject(db.dao().meta(ReminderScheduler.KEY)!!).getJSONObject("scheduled").length())
   val moved=activity(seconds=7200);db.dao().put(moved);scheduler.reconcile(listOf(moved))
   val changed=JSONObject(db.dao().meta(ReminderScheduler.KEY)!!).getJSONObject("scheduled").getJSONObject("a")
   assertEquals(JSONObject(moved.payload).getString("scheduled_at"),changed.getString("scheduled_at"))
   val done=moved.copy(payload=JSONObject(moved.payload).put("status","done").toString());db.dao().put(done);scheduler.reconcile(listOf(done))
   assertEquals(0,JSONObject(db.dao().meta(ReminderScheduler.KEY)!!).getJSONObject("scheduled").length())
  } finally {db.close();context.deleteDatabase("euros.db")}
 }
 @Test fun snoozeSurvivesRestartAndDeletedItemIsCancelled()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("euros.db");val db=EurosDatabase.open(context)
  val r=activity();db.dao().put(r);val scheduler=ReminderScheduler(context,db.dao());scheduler.saveSettings(ReminderSettings(true));scheduler.snooze(r.id,30)
  val before=JSONObject(db.dao().meta(ReminderScheduler.KEY)!!).getJSONObject("snoozes").getJSONObject(r.id).getString("due");db.close()
  val reopened=EurosDatabase.open(context)
  try {val fresh=ReminderScheduler(context,reopened.dao());fresh.reconcile(listOf(r));val state=JSONObject(reopened.dao().meta(ReminderScheduler.KEY)!!);assertEquals(before,state.getJSONObject("scheduled").getJSONObject(r.id).getString("due"));reopened.dao().put(r.copy(deleted=true));fresh.reconcile(emptyList());assertEquals(0,JSONObject(reopened.dao().meta(ReminderScheduler.KEY)!!).getJSONObject("scheduled").length())}
  finally {reopened.close();context.deleteDatabase("euros.db")}
 }
 @Test fun invalidQuietSettingsLeavePriorChoiceIntact()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("euros.db");val db=EurosDatabase.open(context)
  try {val scheduler=ReminderScheduler(context,db.dao());scheduler.saveSettings(ReminderSettings(true,"22:00","07:00"));assertTrue(runCatching{scheduler.saveSettings(ReminderSettings(true,"22:00","22:00"))}.isFailure);assertEquals("07:00",scheduler.settings().quietEnd)}
  finally {db.close();context.deleteDatabase("euros.db")}
 }
}
