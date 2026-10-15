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
 private fun activity(id:String="a",status:String="pending",seconds:Long=7200)=Record("activities",id,1,JSONObject().put("kind","bjj").put("title","BJJ").put("scheduled_at",Instant.now().plusSeconds(seconds).atOffset(ZoneOffset.UTC).toString()).put("status",status).toString())
 @Test fun moveReplacesOneAlarmAndCompletionClearsDesiredState()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("euros.db");val db=EurosDatabase.open(context)
  try {
   val original=activity();db.dao().put(original);val scheduler=ReminderScheduler(context,db.dao());scheduler.saveSettings(ReminderSettings(true));scheduler.reconcile(listOf(original));scheduler.reconcile(listOf(original))
   assertTrue(JSONObject(db.dao().meta(ReminderScheduler.KEY)!!).getJSONObject("scheduled").has("a"))
   val moved=activity(seconds=10800);db.dao().put(moved);scheduler.reconcile(listOf(moved))
   val changed=JSONObject(db.dao().meta(ReminderScheduler.KEY)!!).getJSONObject("scheduled").getJSONObject("a")
   assertEquals(java.time.OffsetDateTime.parse(JSONObject(moved.payload).getString("scheduled_at")).toInstant().minusSeconds(3600).toString(),changed.getString("scheduled_at"))
   val done=moved.copy(payload=JSONObject(moved.payload).put("status","done").toString());db.dao().put(done);scheduler.reconcile(listOf(done))
   assertFalse(JSONObject(db.dao().meta(ReminderScheduler.KEY)!!).getJSONObject("scheduled").has("a"))
  } finally {db.close();context.deleteDatabase("euros.db")}
 }
 @Test fun snoozeSurvivesRestartAndDeletedItemIsCancelled()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("euros.db");val db=EurosDatabase.open(context)
  val r=activity();db.dao().put(r);val scheduler=ReminderScheduler(context,db.dao());scheduler.saveSettings(ReminderSettings(true));scheduler.snooze(r.id,30)
  val before=JSONObject(db.dao().meta(ReminderScheduler.KEY)!!).getJSONObject("snoozes").getJSONObject(r.id).getString("due");db.close()
  val reopened=EurosDatabase.open(context)
  try {val fresh=ReminderScheduler(context,reopened.dao());fresh.reconcile(listOf(r));val state=JSONObject(reopened.dao().meta(ReminderScheduler.KEY)!!);assertEquals(before,state.getJSONObject("scheduled").getJSONObject(r.id).getString("due"));reopened.dao().put(r.copy(deleted=true));fresh.reconcile(emptyList());assertFalse(JSONObject(reopened.dao().meta(ReminderScheduler.KEY)!!).getJSONObject("scheduled").has(r.id))}
  finally {reopened.close();context.deleteDatabase("euros.db")}
 }
 @Test fun invalidQuietSettingsLeavePriorChoiceIntact()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("euros.db");val db=EurosDatabase.open(context)
  try {val scheduler=ReminderScheduler(context,db.dao());scheduler.saveSettings(ReminderSettings(true,"22:00","07:00"));assertTrue(runCatching{scheduler.saveSettings(ReminderSettings(true,"22:00","22:00"))}.isFailure);assertEquals("07:00",scheduler.settings().quietEnd)}
  finally {db.close();context.deleteDatabase("euros.db")}
 }
 private fun scheduled(at:String,status:String="pending")=Record("activities","night",1,JSONObject().put("kind","bjj").put("title","Class").put("scheduled_at",at).put("status",status).toString())
 private fun scheduler(context:Context,db:EurosDatabase,at:String)=ReminderScheduler(context,db.dao(),java.time.Clock.fixed(Instant.parse(at),ZoneOffset.UTC))
 @Test fun overnightSnoozeRetainsPreviousDaysSession()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("euros.db");val db=EurosDatabase.open(context)
  try {
   val r=scheduled("2026-10-15T23:30:00-05:00");db.dao().put(r)
   val before=scheduler(context,db,"2026-10-16T04:00:00Z");before.saveSettings(ReminderSettings(true));before.snoozeUntil(r.id,Instant.parse("2026-10-16T05:30:00Z"))
   scheduler(context,db,"2026-10-16T05:10:00Z").reconcile(listOf(r))
   assertEquals("2026-10-16T05:30:00Z",JSONObject(db.dao().meta(ReminderScheduler.KEY)!!).getJSONObject("scheduled").getJSONObject(r.id).getString("due"))
  }finally{db.close();context.deleteDatabase("euros.db")}
 }
 @Test fun deliveryIsNotRepeatedAndCompletionCancelsVisibleNotification()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("euros.db");val db=EurosDatabase.open(context)
  try {
   val r=scheduled("2026-10-15T18:30:00-05:00");db.dao().put(r)
   scheduler(context,db,"2026-10-15T22:00:00Z").saveSettings(ReminderSettings(true))
   val due=scheduler(context,db,"2026-10-15T22:30:00Z");due.deliver(r.id);due.reconcile(listOf(r))
   val state=JSONObject(db.dao().meta(ReminderScheduler.KEY)!!)
   assertTrue(state.getJSONObject("delivered").has(r.id));assertFalse(state.getJSONObject("scheduled").has(r.id))
   val done=r.copy(payload=JSONObject(r.payload).put("status","done").toString());db.dao().put(done);due.reconcile(listOf(done))
   assertFalse(JSONObject(db.dao().meta(ReminderScheduler.KEY)!!).getJSONObject("delivered").has(r.id))
   assertTrue(context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.none{it.tag==r.id})
  }finally{db.close();context.deleteDatabase("euros.db")}
 }
 @Test fun delayedCallbackDuringQuietHoursDropsInsteadOfRescheduling()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>();context.deleteDatabase("euros.db");val db=EurosDatabase.open(context)
  try {
   val r=scheduled("2026-10-15T22:30:00-05:00");db.dao().put(r)
   scheduler(context,db,"2026-10-16T02:00:00Z").saveSettings(ReminderSettings(true,"22:00","08:00"))
   scheduler(context,db,"2026-10-16T03:15:00Z").deliver(r.id)
   assertFalse(JSONObject(db.dao().meta(ReminderScheduler.KEY)!!).getJSONObject("scheduled").has(r.id))
   assertTrue(context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.none{it.tag==r.id})
  }finally{db.close();context.deleteDatabase("euros.db")}
 }

}
