package com.gwensmuda.euros

import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.security.MessageDigest

data class RichReminder(val id:String,val due:Instant,val title:String,val facts:String,val destination:String,val kind:String,val allowSkipJoke:Boolean=false) {
 val contextKey:String get()=MessageDigest.getInstance("SHA-256").digest(listOf(id,due.toString(),facts,destination,kind,allowSkipJoke.toString()).joinToString("\n").toByteArray()).joinToString(""){"%02x".format(it)}
}
/** All timing and factual claims are local and deterministic. No missing log is a missed activity. */
object RichReminderRules {
 private val clock=DateTimeFormatter.ofPattern("h:mm a",Locale.US)
 private val calendarDate=DateTimeFormatter.ofPattern("MMM d",Locale.US)
 fun plans(records:List<Record>,settings:ReminderSettings,date:LocalDate,zone:ZoneId,meals:List<FoodMealReference>,dismissed:Set<String>,ends:Map<String,Instant>):List<RichReminder> {
  if(!settings.enabled)return emptyList()
  val roots=PlanDomain.onDay(records,date)
  val pending=roots.filter{ReminderRules.isPending(it,records)}
  val remaining=FoodDomain.remainingReferences(meals,records,date,dismissed)
  fun at(time:String)=date.atTime(LocalTime.parse(time)).atZone(zone).toInstant()
  fun title(r:Record)=JSONObject(r.payload).optString("title")
  fun time(r:Record)=OffsetDateTime.parse(JSONObject(r.payload).getString("scheduled_at")).atZoneSameInstant(zone).format(clock)
  val result=mutableListOf<RichReminder>()
  val sunday=date.dayOfWeek==DayOfWeek.SUNDAY
  val morningAt=ReminderRules.afterQuiet(at(if(sunday)"09:00" else "08:00"),settings,zone)
  if(morningAt.atZone(zone).toLocalDate()==date) {
   val weight=records.any{!it.deleted && it.type=="weight_entries" && JSONObject(it.payload).optString("local_date")==date.toString()}
   val facts=if(sunday) {
    if(weight) "Your weight is logged. Let's look at this week's training and food logs." else "Sunday weigh-in, then a look at the week. I've got the logs; you bring the number."
   } else buildList {
    val breakfast=remaining.firstOrNull{it.title.contains("breakfast",true)}
    breakfast?.let{add("For breakfast, the plan is ${it.foods.ifEmpty{listOf(it.title)}.joinToString(", ")}.")}
    pending.filter{JSONObject(it.payload).optString("kind") in setOf("bjj","weights")}.forEach{add("${title(it)} is at ${time(it)}.")}
    pending.filter{JSONObject(it.payload).optString("kind") in setOf("recovery","rest","morning")}.forEach{r->
     val steps=PlanDomain.children(r,records).filter{PlanDomain.status(it)=="pending"}.map(::title)
     add(if(steps.isEmpty()) "${title(r)} is on today's plan." else "Today's routine: ${steps.joinToString(", ")}.")
    }
   }.joinToString(" ").ifBlank{"There are no remaining planned activities or meals for today."}
   result.add(RichReminder("${if(sunday)"sunday" else "morning"}:$date",morningAt,if(sunday)"Sunday review" else "Morning plan",facts,if(sunday)"@review" else "@today",if(sunday)"sunday" else "morning"))
  }
  for(r in pending) {
   val p=JSONObject(r.payload);val kind=p.optString("kind")
   if(kind in setOf("bjj","weights")) {
    val due=OffsetDateTime.parse(p.getString("scheduled_at")).toInstant().minusSeconds(3600)
    val yesterday=PlanDomain.onDay(records,date.minusDays(1)).filter{JSONObject(it.payload).optString("kind")==kind}
    val skip=yesterday.any{PlanDomain.status(it)=="skipped"} && (yesterday+roots).none{PlanDomain.status(it)=="done"}
    if(ReminderRules.afterQuiet(due,settings,zone)==due) result.add(RichReminder(r.id,due,title(r),"${title(r)} is scheduled for ${date.format(calendarDate)} at ${time(r)}."+(if(skip) " You marked the ${date.minusDays(1).format(calendarDate)} ${if(kind=="bjj")"class" else "weights session"} skipped." else ""),r.id,"training",skip))
   }
   if(kind=="recovery") {
    val linked=p.optString("linked_session_id");val session=records.firstOrNull{it.id==linked && !it.deleted}
    if(session!=null && PlanDomain.status(session)!="skipped") {
     val sp=JSONObject(session.payload)
     val minutes=sp.optJSONObject("prescription")?.optDouble("durationMinutes",0.0) ?: 0.0
     val end=ends[linked] ?: if(minutes>0)OffsetDateTime.parse(sp.getString("scheduled_at")).toInstant().plusSeconds((minutes*60).toLong()) else null
     if(end!=null) {
      val due=end.plusSeconds(900)
      val steps=PlanDomain.children(r,records).filter{PlanDomain.status(it)=="pending"}.map{child->val cp=JSONObject(child.payload);if(cp.optString("recovery_step_type")=="hydration")"12–20 oz of water" else title(child)}
      if(ReminderRules.afterQuiet(due,settings,zone)==due)result.add(RichReminder(r.id,due,"Recovery","Time for the post-session routine: ${steps.ifEmpty{listOf(title(r))}.joinToString(", ")}.",r.id,"recovery"))
     }
    }
   }
  }
  val foodAt=at("19:30")
  if(ReminderRules.afterQuiet(foodAt,settings,zone)==foodAt) result.add(RichReminder("food:$date",foodAt,"Food check-in","What did you eat? Tell me in one go and I'll sort out the numbers."+(if(remaining.isNotEmpty())" Still on the plan: ${remaining.joinToString(", "){it.title}}." else ""),"@food","food"))
  return result
 }
}
