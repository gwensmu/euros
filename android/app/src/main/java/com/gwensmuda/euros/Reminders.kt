package com.gwensmuda.euros

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

data class ReminderSettings(val enabled:Boolean=false,val quietStart:String?=null,val quietEnd:String?=null)
data class ReminderPlan(val activityId:String,val due:Instant,val scheduledAt:String,val title:String)
object ReminderRules {
 fun isPending(record:Record,records:List<Record>):Boolean {
  if(record.deleted || record.type!="activities" || JSONObject(record.payload).optString("status")!="pending") return false
  val children=records.filter{!it.deleted && it.type=="activities" && JSONObject(it.payload).optString("parent_id")==record.id}
  return children.isEmpty() || children.any{JSONObject(it.payload).optString("status") !in setOf("done","skipped")}
 }
 fun due(record:Record,settings:ReminderSettings,now:Instant,zone:ZoneId,snoozed:Instant?=null):ReminderPlan? {
  if(!settings.enabled || record.deleted || record.type!="activities") return null
  val p=JSONObject(record.payload);if(p.optString("status")!="pending") return null
  val scheduled=p.getString("scheduled_at");var at=snoozed ?: OffsetDateTime.parse(scheduled).toInstant()
  // No invented overdue times and no burst of old reminders on reboot/permission change.
  if(at<=now) return null
  at=afterQuiet(at,settings,zone)
  return ReminderPlan(record.id,at,scheduled,p.optString("title"))
 }
 fun afterQuiet(at:Instant,settings:ReminderSettings,zone:ZoneId):Instant {
  val start=settings.quietStart?.let{LocalTime.parse(it)} ?: return at
  val end=settings.quietEnd?.let{LocalTime.parse(it)} ?: return at
  require(start!=end){"Quiet hours must have different start and end times"}
  val local=at.atZone(zone);val clock=local.toLocalTime()
  val quiet=if(start<end) clock>=start && clock<end else clock>=start || clock<end
  if(!quiet) return at
  val endDate=if(start>end && clock>=start) local.toLocalDate().plusDays(1) else local.toLocalDate()
  return endDate.atTime(end).atZone(zone).toInstant()
 }
}
class ReminderScheduler(private val context:Context,private val dao:EurosDao) {
 companion object { const val KEY="reminders_v1";const val CHANNEL="euros_activity";private val mutex=Mutex() }
 private suspend fun state()=dao.meta(KEY)?.let{JSONObject(it)} ?: JSONObject().put("enabled",false).put("scheduled",JSONObject()).put("snoozes",JSONObject())
 private fun decode(s:JSONObject)=ReminderSettings(s.optBoolean("enabled"),s.optString("quiet_start").takeIf{it.isNotBlank()},s.optString("quiet_end").takeIf{it.isNotBlank()})
 suspend fun settings():ReminderSettings=decode(state())
 suspend fun saveSettings(settings:ReminderSettings) { require((settings.quietStart==null)==(settings.quietEnd==null));settings.quietStart?.let{LocalTime.parse(it)};settings.quietEnd?.let{LocalTime.parse(it)};require(settings.quietStart==null || settings.quietStart!=settings.quietEnd)
  mutex.withLock { val s=state().put("enabled",settings.enabled).put("quiet_start",settings.quietStart ?: "").put("quiet_end",settings.quietEnd ?: "");dao.meta(Metadata(KEY,s.toString())) }
  reconcile(dao.observe().first())
 }
 fun permissionGranted()=(Build.VERSION.SDK_INT<33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) && context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
 suspend fun reconcile(records:List<Record>)=mutex.withLock {
  val s=state();val settings=decode(s);val previous=s.getJSONObject("scheduled");val snoozes=s.getJSONObject("snoozes");val next=JSONObject();val delivered=s.optJSONObject("delivered") ?: JSONObject();val plans=mutableListOf<ReminderPlan>();val now=Instant.now();val zone=ZoneId.systemDefault()
  for(record in records) {
   val snooze=snoozes.optJSONObject(record.id)
   val scheduled=JSONObject(record.payload).optString("scheduled_at")
   val snoozed=if(snooze?.optString("scheduled_at")==scheduled) Instant.parse(snooze.getString("due")) else null
   if(snooze!=null && snoozed==null) snoozes.remove(record.id)
   val plan=if(permissionGranted() && ReminderRules.isPending(record,records)) ReminderRules.due(record,settings,now,zone,snoozed) else null
   if(plan!=null) { plans.add(plan);next.put(record.id,JSONObject().put("due",plan.due.toString()).put("scheduled_at",plan.scheduledAt)) }
  }
  val active=records.filter{ReminderRules.isPending(it,records)}.associateBy{it.id}
  delivered.keys().asSequence().toList().forEach{id->val record=active[id]
   if(!settings.enabled || !permissionGranted() || record==null || delivered.optString(id)!=JSONObject(record.payload).optString("scheduled_at")) {context.getSystemService(NotificationManager::class.java).cancel(id,0);delivered.remove(id)}
  }
  // Save desired state before platform effects. Reconciliation can safely repeat after a crash.
  s.put("scheduled",next).put("delivered",delivered);dao.meta(Metadata(KEY,s.toString()))
  val alarm=context.getSystemService(AlarmManager::class.java)
  previous.keys().forEach{id->if(!next.has(id)) { alarm.cancel(intent(id));context.getSystemService(NotificationManager::class.java).cancel(id,0) }}
  plans.forEach{p->alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,p.due.toEpochMilli(),intent(p.activityId))}
 }
 private fun intent(id:String)=PendingIntent.getBroadcast(context,0,Intent(context,ReminderReceiver::class.java).setAction("euros.reminder").setData(Uri.parse("euros://reminder/"+Uri.encode(id))).putExtra("activity_id",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
 suspend fun snooze(activityId:String,minutes:Int) {
  require(minutes in 1..1440)
  mutex.withLock {
   val r=dao.record("activities",activityId) ?: error("Activity unavailable");check(ReminderRules.isPending(r,dao.observe().first()))
   val s=state();s.getJSONObject("snoozes").put(activityId,JSONObject().put("due",Instant.now().plusSeconds(minutes*60L).toString()).put("scheduled_at",JSONObject(r.payload).getString("scheduled_at")));dao.meta(Metadata(KEY,s.toString()))
  }
  context.getSystemService(NotificationManager::class.java).cancel(activityId,0);reconcile(dao.observe().first())
 }
 suspend fun deliver(activityId:String)=mutex.withLock {
  if(!permissionGranted()) return@withLock
  val s=state();val settings=decode(s);if(!settings.enabled) return@withLock
  val wanted=s.getJSONObject("scheduled").optJSONObject(activityId) ?: return@withLock
  val r=dao.record("activities",activityId) ?: return@withLock;val p=JSONObject(r.payload)
  if(!ReminderRules.isPending(r,dao.observe().first()) || wanted.getString("scheduled_at")!=p.optString("scheduled_at")) return@withLock
  val now=Instant.now();val due=Instant.parse(wanted.getString("due"))
  if(now<due) return@withLock
  // A delayed OS callback is not permission to notify during quiet hours.
  if(ReminderRules.afterQuiet(now,settings,ZoneId.systemDefault())!=now) {
   val next=ReminderRules.afterQuiet(now,settings,ZoneId.systemDefault());wanted.put("due",next.toString());dao.meta(Metadata(KEY,s.toString()));context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,next.toEpochMilli(),intent(activityId));return@withLock
  }
  val manager=context.getSystemService(NotificationManager::class.java);manager.createNotificationChannel(NotificationChannel(CHANNEL,"Activity check-ins",NotificationManager.IMPORTANCE_DEFAULT))
  val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java).setData(Uri.parse("euros://activity/"+Uri.encode(activityId))).putExtra("activity_id",activityId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val snooze=PendingIntent.getBroadcast(context,0,Intent(context,ReminderReceiver::class.java).setAction("euros.snooze").setData(Uri.parse("euros://snooze/"+Uri.encode(activityId))).putExtra("activity_id",activityId),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val notification=Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_edit).setContentTitle(p.optString("title")).setContentText("Log this activity").setContentIntent(open).setAutoCancel(true).addAction(Notification.Action.Builder(null,"Log",open).build()).addAction(Notification.Action.Builder(null,"Snooze 30 min",snooze).build()).build()
  manager.notify(activityId,0,notification)
  val delivered=s.optJSONObject("delivered") ?: JSONObject();delivered.put(activityId,p.getString("scheduled_at"));s.put("delivered",delivered)
  s.getJSONObject("scheduled").remove(activityId);s.getJSONObject("snoozes").remove(activityId);dao.meta(Metadata(KEY,s.toString()))
 }
}
class ReminderReceiver:BroadcastReceiver() {
 override fun onReceive(context:Context,intent:Intent) {
  val pending=goAsync();CoroutineScope(Dispatchers.IO).launch {
   try { val dao=(context.applicationContext as EurosApplication).repository.dao;val scheduler=ReminderScheduler(context,dao)
    val id=intent.getStringExtra("activity_id")
    when { intent.action=="euros.snooze" && id!=null -> scheduler.snooze(id,30); intent.action=="euros.reminder" && id!=null -> scheduler.deliver(id);else -> scheduler.reconcile(dao.observe().first()) }
   } catch(_:Exception) { /* Retry reconciliation on the next foreground run; never log record content. */ } finally { pending.finish() }
  }
 }
}
