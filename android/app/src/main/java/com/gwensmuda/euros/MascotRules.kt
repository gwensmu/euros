package com.gwensmuda.euros

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

enum class MascotDisposition { NORMAL, JUDGY }
enum class MascotPose { IDLE, PLEASED, JUDGY, TRAINING, RECOVERY, REST }
data class MascotState(val disposition:MascotDisposition,val pose:MascotPose,val qualifyingSkipIds:Set<String>)

/** Recomputed from current records; no calorie, weight, overdue-item or persisted judgment state. */
object MascotRules {
 private data class Action(val record:Record,val payload:JSONObject)
 private data class Outcome(val status:String,val at:Instant?)
 private fun actions(records:List<Record>):List<Action> = records.filter{it.type=="activities"}
  .groupBy{it.id}.values.map{it.maxWith(compareBy<Record>{r->r.revision}.thenBy{r->r.deleted})}
  .filterNot{it.deleted}.mapNotNull{r->runCatching{Action(r,JSONObject(r.payload))}.getOrNull()}
 private fun timestamp(payload:JSONObject,status:String):Instant? {
  val key=if(status=="done")"completed_at" else "skipped_at"
  // Legacy records have only a scheduled time. Newly logged actions use their actual timestamp.
  val raw=payload.optString(key).takeIf{it.isNotBlank() && it!="null"} ?: payload.optString("scheduled_at")
  return runCatching{OffsetDateTime.parse(raw).toInstant()}.getOrNull()
 }
 fun evaluate(records:List<Record>,now:Instant,zone:ZoneId=PlanDomain.zone,nextItem:Record?=null):MascotState {
  val actions=actions(records)
  val children=actions.groupBy{it.payload.optString("parent_id")}
  fun recoveryRoutine(action:Action):Boolean = action.payload.optString("kind") in setOf("recovery","rest") &&
   children[action.record.id].orEmpty().let{it.isNotEmpty() && it.all{step->step.payload.optString("kind")=="recovery"}}
  fun outcome(action:Action,seen:Set<String> = emptySet()):Outcome {
   if(action.record.id in seen)return Outcome("pending",null)
   val steps=if(recoveryRoutine(action))children[action.record.id].orEmpty() else emptyList()
   if(steps.isEmpty()) {
    val status=action.payload.optString("status","pending")
    return Outcome(status,timestamp(action.payload,status))
   }
   val outcomes=steps.map{outcome(it,seen+action.record.id)}
   val status=when {outcomes.all{it.status=="done"}->"done";outcomes.all{it.status=="skipped"}->"skipped";else->"pending"}
   // An unknown timestamp cannot invent a chronological judgment for a whole routine.
   return Outcome(status,if(outcomes.any{it.at==null})null else outcomes.mapNotNull{it.at}.maxOrNull())
  }
  val events=actions.filter{it.payload.optString("parent_id").isBlank()}.mapNotNull { action ->
   val kind=action.payload.optString("kind")
   if(kind !in setOf("bjj","weights","recovery") && !recoveryRoutine(action))return@mapNotNull null
   val result=outcome(action)
   if(result.at==null || result.at>now || result.status !in setOf("done","skipped"))null else action.record.id to result
  }
  val clearedAt=events.filter{it.second.status=="done"}.maxOfOrNull{it.second.at!!}
  val firstDay=now.atZone(zone).toLocalDate().minusDays(2)
  val skips=events.filter { (_,event)->event.status=="skipped" &&
   !event.at!!.atZone(zone).toLocalDate().isBefore(firstDay) && (clearedAt==null || event.at>=clearedAt)
  }.map{it.first}.toSet()
  val disposition=if(skips.size>=2)MascotDisposition.JUDGY else MascotDisposition.NORMAL
  val context=nextItem?.takeUnless{it.deleted}?.let{runCatching{JSONObject(it.payload).optString("kind")}.getOrNull()}
  val pose=if(disposition==MascotDisposition.JUDGY)MascotPose.JUDGY else when(context) {
   "bjj","weights"->MascotPose.TRAINING
   "recovery","morning"->MascotPose.RECOVERY
   "rest"->MascotPose.REST
   else->MascotPose.IDLE
  }
  return MascotState(disposition,pose,skips)
 }
}
