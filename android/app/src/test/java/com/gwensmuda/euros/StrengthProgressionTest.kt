package com.gwensmuda.euros

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StrengthProgressionTest {
 private fun session(id:String,date:String,status:String="pending",feeling:String?=null,name:String="Row",reps:Any=10):Record {
  val template=JSONObject().put("exercises",JSONArray().put(JSONObject().put("name",name).put("sets",3).put("reps",reps).put("loadLb",25)).put(JSONObject().put("name","Plank").put("sets",2).put("holdSeconds",30)))
  val p=JSONObject().put("kind","weights").put("title","Weights").put("status",status).put("scheduled_at","${date}T11:00:00-06:00").put("prescription",template)
  if(status=="done")p.put("completed_at","${date}T12:00:00-06:00")
  if(feeling!=null)p.put("strength_feedback",JSONObject().put("feeling",feeling).put("source","selected"))
  return Record("activities",id,1,p.toString())
 }
 private fun changed(target:Record,records:List<Record>)=StrengthProgression.updates(records).first{it.first.id==target.id}.second
 @Test fun easyChangesRepsAndHoldsWithoutChangingLoadsSetsOrAuthoredSnapshot() {
  val a=session("a","2026-11-01","done","easy");val b=session("b","2026-11-08")
  val p=changed(b,listOf(a,b));val ex=StrengthProgression.template(p).getJSONArray("exercises")
  assertEquals(11,ex.getJSONObject(0).getInt("reps"));assertEquals(35,ex.getJSONObject(1).getInt("holdSeconds"))
  assertEquals(25,ex.getJSONObject(0).getInt("loadLb"));assertEquals(3,ex.getJSONObject(0).getInt("sets"))
  assertEquals(JSONObject(b.payload).getJSONObject("prescription").toString(),p.getJSONObject("prescription").toString())
  assertTrue(StrengthProgression.updates(listOf(a,b.copy(payload=p.toString()))).isEmpty())
 }
 @Test fun separateRoutineSkipsUnknownAndDeletedFeedbackDoNotProgress() {
  val b=session("b","2026-11-08")
  listOf(session("a","2026-11-01","done","easy",name="Squat"),session("a","2026-11-01","skipped","easy"),session("a","2026-11-01","done"),session("a","2026-11-01","done","easy").copy(deleted=true),session("a","2026-11-15","done","easy")).forEach {a->assertTrue(StrengthProgression.updates(listOf(a,b)).isEmpty())}
 }
 @Test fun subsequentCompletionCarriesTheAdjustedPrescriptionAndCorrectionsRecomputePendingOnly() {
  val a=session("a","2026-11-01","done","easy");val b=session("b","2026-11-08");val c=session("c","2026-11-15")
  val bp=changed(b,listOf(a,b)).put("status","done").put("completed_at","2026-11-08T12:00:00-06:00").put("strength_feedback",StrengthFeedback.value(StrengthFeeling.ABOUT_RIGHT,"default"))
  val done=b.copy(payload=bp.toString())
  val cp=changed(c,listOf(a,done,c))
  assertEquals(11,StrengthProgression.template(cp).getJSONArray("exercises").getJSONObject(0).getInt("reps"))
  assertTrue(StrengthProgression.updates(listOf(a,done,c)).none{it.first.id=="b"})
  val hard=done.copy(payload=JSONObject(done.payload).put("strength_feedback",StrengthFeedback.value(StrengthFeeling.TOO_HARD,"selected")).toString())
  val corrected=changed(c.copy(payload=cp.toString()),listOf(a,hard,c.copy(payload=cp.toString())))
  assertEquals(10,StrengthProgression.template(corrected).getJSONArray("exercises").getJSONObject(0).getInt("reps"))
 }
 @Test fun undoRemovesPendingAdjustmentAndTextRangesRemainUntouched() {
  val a=session("a","2026-11-01","done","easy");val b=session("b","2026-11-08")
  val adjusted=b.copy(payload=changed(b,listOf(a,b)).toString())
  assertFalse(changed(adjusted,listOf(a.copy(deleted=true),adjusted)).has(StrengthProgression.KEY))
  val rangeA=session("a","2026-11-01","done","easy",reps="8–12");val rangeB=session("b","2026-11-08",reps="8–12")
  assertEquals("8–12",StrengthProgression.template(changed(rangeB,listOf(rangeA,rangeB))).getJSONArray("exercises").getJSONObject(0).getString("reps"))
 }
}
