package com.gwensmuda.euros

import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

/** Adjust prescriptions, never claimed performance. Original authored snapshots stay intact. */
object StrengthProgression {
 const val KEY="strength_progression"
 private fun base(p:JSONObject):JSONObject = p.optJSONObject("plan_snapshot")?.optJSONObject("template") ?: p.optJSONObject("prescription") ?: JSONObject()
 fun template(p:JSONObject):JSONObject = p.optJSONObject(KEY)?.optJSONObject("template") ?: base(p)
 private fun canonical(value:Any?):String=when(value) {
  is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix="{",postfix="}"){JSONObject.quote(it)+":"+canonical(value.get(it))}
  is JSONArray -> (0 until value.length()).joinToString(prefix="[",postfix="]"){canonical(value.get(it))}
  null,JSONObject.NULL -> "null"
  is String -> JSONObject.quote(value)
  else -> value.toString()
 }
 private fun signature(p:JSONObject):String? {
  val exercises=base(p).optJSONArray("exercises") ?: return null
  if(exercises.length()==0)return null
  // An authored change to any exercise/load/set/rep/hold starts a different prescription.
  return p.optString("phase_id")+":"+canonical(exercises)
 }
 private fun instant(p:JSONObject,key:String)=runCatching{OffsetDateTime.parse(p.getString(key)).toInstant()}.getOrNull()
 fun updates(records:List<Record>):List<Pair<Record,JSONObject>> {
  val weights=records.filter{it.type=="activities" && !it.deleted && JSONObject(it.payload).optString("kind")=="weights"}
  return weights.filter{JSONObject(it.payload).optString("status")=="pending"}.mapNotNull { target ->
   val p=JSONObject(target.payload);val sig=signature(p);val scheduled=instant(p,"scheduled_at")
   val source=if(sig==null || scheduled==null)null else weights.filter { prior ->
    val s=JSONObject(prior.payload)
    prior.id!=target.id && s.optString("status")=="done" && signature(s)==sig &&
     s.optJSONObject("strength_feedback")?.optString("feeling") in StrengthFeeling.entries.map{it.wire} &&
     instant(s,"completed_at")?.isBefore(scheduled)==true && instant(s,"scheduled_at")?.isBefore(scheduled)==true
   }.maxWithOrNull(compareBy<Record>{instant(JSONObject(it.payload),"completed_at")}.thenBy{it.id})
   p.remove(KEY)
   if(source!=null) {
    val s=JSONObject(source.payload)
    val feeling=StrengthFeeling.fromWire(s.getJSONObject("strength_feedback").getString("feeling"))!!
    val adjustment=when(feeling){StrengthFeeling.EASY->1;StrengthFeeling.ABOUT_RIGHT->0;StrengthFeeling.TOO_HARD->-1}
    val next=JSONObject(template(s).toString());val exercises=next.getJSONArray("exercises")
    var repsChanged=false;var holdsChanged=false
    for(i in 0 until exercises.length()) {
     val ex=exercises.getJSONObject(i)
     // Leave ranges/free text/missing values alone; do not manufacture numeric prescriptions.
     val reps=ex.opt("reps")
     if(reps is Number && reps.toDouble()==reps.toInt().toDouble() && reps.toInt() in 1..30) {
      val value=(reps.toInt()+adjustment).coerceIn(1,30)
      if(value!=reps.toInt()){ex.put("reps",value);repsChanged=true}
     }
     val hold=ex.opt("holdSeconds")
     if(hold is Number && hold.toDouble()==hold.toInt().toDouble() && hold.toInt() in 5..120) {
      val value=(hold.toInt()+5*adjustment).coerceIn(5,120)
      if(value!=hold.toInt()){ex.put("holdSeconds",value);holdsChanged=true}
     }
    }
    val changes=buildList {
     if(repsChanged)add(if(adjustment>0)"One more rep per set" else "One fewer rep per set")
     if(holdsChanged)add(if(adjustment>0)"Holds 5 seconds longer" else "Holds 5 seconds shorter")
    }
    val summary=if(changes.isEmpty())"Same reps, holds and weights as last time" else changes.joinToString(" · ")+" · Same weights"
    p.put(KEY,JSONObject().put("policy","fixed-load-v1").put("source_activity_id",source.id).put("source_feeling",feeling.wire).put("template",next).put("summary",summary))
   }
   if(canonical(p)==canonical(JSONObject(target.payload)))null else target to p
  }
 }
}
