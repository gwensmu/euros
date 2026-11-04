package com.gwensmuda.euros

import androidx.health.connect.client.records.ExerciseSessionRecord
import org.json.JSONObject
import java.time.Duration
import java.time.OffsetDateTime

/** A match joins evidence, never creates a completion or changes the scheduled instant. */
object SessionLinking {
 fun proposals(activities:List<Record>,snapshot:JSONObject):Map<String,String> {
  val links=snapshot.optJSONObject("links") ?: JSONObject()
  val excluded=snapshot.optJSONObject("auto_link_exclusions") ?: JSONObject()
  val records=snapshot.optJSONObject("records") ?: return emptyMap()
  val used=links.keys().asSequence().map{links.getString(it)}.toSet()
  val candidates=activities.filter{it.type=="activities" && !it.deleted && !links.has(it.id)}.associate { activity ->
   val p=JSONObject(activity.payload)
   val kind=p.optString("kind")
   val at=p.optString("scheduled_at").takeIf{it.isNotBlank()}?.let{OffsetDateTime.parse(it).toInstant()}
   val matches=if(at==null || kind !in setOf("bjj","weights") || p.optString("status")=="skipped") emptyList() else records.keys().asSequence().filter{it !in used}.filter { key ->
    val r=records.getJSONObject(key)
    val type=r.optInt("exercise_type",-1)
    val compatible=when(kind){
     "bjj"->type==ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS
     "weights"->type in setOf(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING)
     else->false
    }
    HealthSources.isTraining(r) && compatible && excluded.optJSONObject(activity.id)?.optBoolean(key)!=true &&
     Duration.between(at,java.time.Instant.parse(r.getString("start"))).abs()<=Duration.ofMinutes(90)
   }.toList()
   activity.id to matches
  }
  val counts=candidates.values.flatten().groupingBy{it}.eachCount()
  return candidates.mapNotNull{(id,matches)->matches.singleOrNull()?.takeIf{counts[it]==1}?.let{id to it}}.toMap()
 }
 fun exclude(snapshot:JSONObject,activityId:String,key:String) {
  val excluded=snapshot.optJSONObject("auto_link_exclusions") ?: JSONObject().also{snapshot.put("auto_link_exclusions",it)}
  val activity=excluded.optJSONObject(activityId) ?: JSONObject().also{excluded.put(activityId,it)}
  activity.put(key,true)
 }
}
