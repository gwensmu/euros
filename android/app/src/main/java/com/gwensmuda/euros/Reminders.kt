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
class ReminderScheduler(private val context:Context,private val dao:EurosDao,private val clock:java.time.Clock=java.time.Clock.systemUTC()) {
 companion object { const val KEY="reminders_v1";const val CHANNEL="euros_activity";private val mutex=Mutex() }
 private suspend fun state()=dao.meta(KEY)?.let{JSONObject(it)} ?: JSONObject().put("enabled",false).put("scheduled",JSONObject()).put("snoozes",JSONObject())
 private fun decode(s:JSONObject)=ReminderSettings(s.optBoolean("enabled"),s.optString("quiet_start").takeIf{it.isNotBlank()},s.optString("quiet_end").takeIf{it.isNotBlank()})
 suspend fun settings():ReminderSettings=decode(state())
 suspend fun saveSettings(settings:ReminderSettings) { require((settings.quietStart==null)==(settings.quietEnd==null));settings.quietStart?.let{LocalTime.parse(it)};settings.quietEnd?.let{LocalTime.parse(it)};require(settings.quietStart==null || settings.quietStart!=settings.quietEnd)
  mutex.withLock { val s=state().put("enabled",settings.enabled).put("quiet_start",settings.quietStart ?: "").put("quiet_end",settings.quietEnd ?: "");dao.meta(Metadata(KEY,s.toString())) }
  reconcile(dao.observe().first())
 }
 fun permissionGranted()=(Build.VERSION.SDK_INT<33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) && context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
 private suspend fun candidates(records:List<Record>,now:Instant):List<RichReminder> {
  val zone=PlanDomain.zone;val date=now.atZone(zone).toLocalDate();val settings=settings()
  val plan=AuthoredPlan(context).json()
  val snapshot=dao.meta(HealthImport.KEY)?.let{HealthSnapshotStorage.decode(it)}
  val links=snapshot?.optJSONObject("links");val imported=snapshot?.optJSONObject("records")
  val ends=links?.keys()?.asSequence()?.mapNotNull{id->val r=imported?.optJSONObject(links.getString(id));if(r!=null && !r.optBoolean("deleted") && r.optString("kind")=="exercise") id to Instant.parse(r.getString("end")) else null}?.toMap() ?: emptyMap()
  return (-1L..7L).flatMap{offset->val day=date.plusDays(offset)
   RichReminderRules.plans(records,settings,day,zone,FoodDomain.mealReferences(plan,day,records),MealDismissals.decode(dao.meta("dismissed_meals:$day")),ends)
  }
 }
 suspend fun reconcile(records:List<Record>):Unit=mutex.withLock {
  val s=state();val previous=s.getJSONObject("scheduled");val snoozes=s.getJSONObject("snoozes");val next=JSONObject();val delivered=s.optJSONObject("delivered") ?: JSONObject();val now=Instant.now(clock)
  val plans=if(permissionGranted())candidates(records,now) else emptyList()
  val active=plans.associateBy{it.id}
  val alarm=context.getSystemService(AlarmManager::class.java);val manager=context.getSystemService(NotificationManager::class.java)
  delivered.keys().asSequence().toList().forEach{id->if(active[id]?.due?.toString()!=delivered.optString(id)){manager.cancel(id,0);delivered.remove(id)}}
  val deliveredContext=s.optJSONObject("delivered_context") ?: JSONObject()
  deliveredContext.keys().asSequence().toList().forEach{id->if(active[id]?.contextKey!=deliveredContext.optString(id)){manager.cancel(id,0);deliveredContext.remove(id)}}
  snoozes.keys().asSequence().toList().filter{it !in active}.forEach{snoozes.remove(it)}
  for(p in plans) {
   val snooze=snoozes.optJSONObject(p.id)
   val snoozed=if(snooze?.optString("scheduled_at")==p.due.toString())Instant.parse(snooze.getString("due")) else null
   if(snooze!=null && snoozed==null)snoozes.remove(p.id)
   val due=snoozed ?: p.due
   // Preserve a pending alarm briefly when unrelated records change; never replay old reminders.
   val existing=previous.optJSONObject(p.id)?.optString("scheduled_at")==p.due.toString()
   if((due>now || (existing && now.epochSecond-due.epochSecond<7200)) && (snoozed!=null || !delivered.has(p.id))) {
    next.put(p.id,JSONObject().put("due",due.toString()).put("scheduled_at",p.due.toString()))
   }
  }
  s.put("scheduled",next).put("delivered",delivered).put("delivered_context",deliveredContext);dao.meta(Metadata(KEY,s.toString()))
  previous.keys().forEach{id->if(!next.has(id)){alarm.cancel(intent(id));if(!delivered.has(id))manager.cancel(id,0)}}
  next.keys().forEach{id->alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,Instant.parse(next.getJSONObject(id).getString("due")).toEpochMilli(),intent(id))}
  ReminderCopyCache(context,dao).prefetch(plans.filter{it.due>now && it.due<now.plusSeconds(86400)})
 }
 private fun intent(id:String)=PendingIntent.getBroadcast(context,0,Intent(context,ReminderReceiver::class.java).setAction("euros.reminder").setData(Uri.parse("euros://reminder/"+Uri.encode(id))).putExtra("activity_id",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
 suspend fun snooze(activityId:String,minutes:Int) {require(minutes in 1..1440);snoozeUntil(activityId,Instant.now(clock).plusSeconds(minutes*60L))}
 suspend fun snoozeUntil(activityId:String,until:Instant) {
  val now=Instant.now(clock);require(until>now && until<=now.plusSeconds(86400)){"Choose a time in the next 24 hours"}
  mutex.withLock {
   val plan=candidates(dao.observe().first(),now).firstOrNull{it.id==activityId} ?: error("Reminder no longer available")
   require(!plan.destination.startsWith("@") || until.atZone(PlanDomain.zone).toLocalDate()==plan.due.atZone(PlanDomain.zone).toLocalDate()){"Choose a time on the reminder’s date"}
   require(ReminderRules.afterQuiet(until,settings(),PlanDomain.zone)==until){"Choose a time outside quiet hours"}
   val s=state();s.getJSONObject("snoozes").put(activityId,JSONObject().put("due",until.toString()).put("scheduled_at",plan.due.toString()));dao.meta(Metadata(KEY,s.toString()))
  }
  context.getSystemService(NotificationManager::class.java).cancel(activityId,0);reconcile(dao.observe().first())
 }
 suspend fun deliver(activityId:String) {
  mutex.withLock {
   if(!permissionGranted())return@withLock
   val s=state();if(!decode(s).enabled)return@withLock
   val wanted=s.getJSONObject("scheduled").optJSONObject(activityId) ?: return@withLock
   val now=Instant.now(clock);val due=Instant.parse(wanted.getString("due"))
   if(now<due)return@withLock
   val p=candidates(dao.observe().first(),now).firstOrNull{it.id==activityId && it.due.toString()==wanted.getString("scheduled_at")}
   // Quiet/very late callbacks are dropped, not shifted into a burst. Morning is the overview.
   s.getJSONObject("scheduled").remove(activityId);s.getJSONObject("snoozes").remove(activityId)
   if(p==null || now.epochSecond-due.epochSecond>7200 || ReminderRules.afterQuiet(now,decode(s),PlanDomain.zone)!=now){dao.meta(Metadata(KEY,s.toString()));return@withLock}
   val manager=context.getSystemService(NotificationManager::class.java);manager.createNotificationChannel(NotificationChannel(CHANNEL,"Weasel reminders",NotificationManager.IMPORTANCE_DEFAULT))
   val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java).setData(Uri.parse("euros://activity/"+Uri.encode(p.destination))).putExtra("activity_id",p.destination).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
   val joke=ReminderCopyCache(context,dao).joke(p)
   val copy=p.facts+(joke?.let{" $it"} ?: "")
   val builder=Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_edit).setContentTitle(p.title).setContentText(copy).setStyle(Notification.BigTextStyle().bigText(copy)).setContentIntent(open).setAutoCancel(true).addAction(Notification.Action.Builder(null,"Open",open).build())
   for(minutes in listOf(30)) {
    val snooze=PendingIntent.getBroadcast(context,0,Intent(context,ReminderReceiver::class.java).setAction("euros.snooze").setData(Uri.parse("euros://snooze/$minutes/"+Uri.encode(activityId))).putExtra("activity_id",activityId).putExtra("minutes",minutes),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    builder.addAction(Notification.Action.Builder(null,"Snooze $minutes min",snooze).build())
   }
   val choose=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java).setData(Uri.parse("euros://snooze-picker/"+Uri.encode(activityId))).putExtra("activity_id","@snooze:$activityId").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
   builder.addAction(Notification.Action.Builder(null,"Snooze…",choose).build())
   manager.notify(activityId,0,builder.build())
   val delivered=s.optJSONObject("delivered") ?: JSONObject();delivered.put(activityId,p.due.toString());s.put("delivered",delivered);val contexts=s.optJSONObject("delivered_context") ?: JSONObject();contexts.put(activityId,p.contextKey);s.put("delivered_context",contexts);dao.meta(Metadata(KEY,s.toString()))
  }
  reconcile(dao.observe().first())
 }

}
class ReminderReceiver:BroadcastReceiver() {
 override fun onReceive(context:Context,intent:Intent) {
  val pending=goAsync();CoroutineScope(Dispatchers.IO).launch {
   try { val dao=(context.applicationContext as EurosApplication).repository.dao;val scheduler=ReminderScheduler(context,dao)
    val id=intent.getStringExtra("activity_id")
    when { intent.action=="euros.snooze" && id!=null -> scheduler.snooze(id,intent.getIntExtra("minutes",30)); intent.action=="euros.reminder" && id!=null -> scheduler.deliver(id);else -> scheduler.reconcile(dao.observe().first()) }
   } catch(_:Exception) { /* Retry reconciliation on the next foreground run; never log record content. */ } finally { pending.finish() }
  }
 }
}
