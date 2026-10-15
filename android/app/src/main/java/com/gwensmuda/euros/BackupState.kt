package com.gwensmuda.euros

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/** Only portable presentation state. Never copy enrollment, sync cursors or platform alarms. */
object BackupState {
 fun portable(key:String)=key.startsWith("draft:") || key.startsWith("dismissed_meals:") || key==ReminderScheduler.KEY
 fun export(metadata:List<Metadata>)=JSONObject().apply {
  metadata.filter{portable(it.key)}.forEach { item ->
   val value=if(item.key==ReminderScheduler.KEY) {
    val raw=JSONObject(item.value)
    JSONObject().put("enabled",false).put("quiet_start",raw.optString("quiet_start")).put("quiet_end",raw.optString("quiet_end")).put("scheduled",JSONObject()).put("snoozes",JSONObject()).toString()
   } else item.value
   put(item.key,value)
  }
 }
 fun parse(archive:JSONObject):List<Metadata> {
  val state=archive.optJSONObject("local_state") ?: JSONObject()
  require(state.length()<=20000){"Too many local settings"}
  return state.keys().asSequence().map { key ->
   require(portable(key)){"Unsupported local setting"}
   val value=state.getString(key)
   require(value.length<=1_000_000){"Local setting is too large"}
   when {
    key.startsWith("draft:")->require(key.length in 7..300)
    key.startsWith("dismissed_meals:")-> {
     LocalDate.parse(key.removePrefix("dismissed_meals:"))
     val ids=JSONArray(value);require(ids.length()<=1000)
     for(i in 0 until ids.length())require(ids.getString(i).isNotBlank() && ids.getString(i).length<=200)
    }
    key==ReminderScheduler.KEY->{
     val raw=JSONObject(value)
     val start=raw.optString("quiet_start");val end=raw.optString("quiet_end")
     require(start.isBlank()==end.isBlank())
     if(start.isNotBlank()){LocalTime.parse(start);LocalTime.parse(end)}
     return@map Metadata(key,JSONObject().put("enabled",false).put("quiet_start",start).put("quiet_end",end).put("scheduled",JSONObject()).put("snoozes",JSONObject()).toString())
    }
   }
   Metadata(key,value)
  }.toList()
 }
 fun health(archive:JSONObject):JSONObject? {
  if(!archive.has("health_imports"))return null
  require(archive.getBoolean("health_included")){"Tracker snapshot was not selected"}
  val state=archive.getJSONObject("health_imports")
  require(state.getInt("schema")==1){"Unsupported tracker snapshot"}
  val records=state.getJSONObject("records");val links=state.getJSONObject("links")
  for(key in records.keys()) {
   val record=records.getJSONObject(key)
   require(record.getString("kind") in setOf("exercise","heart_rate","weight"))
   require(record.getString("source").isNotBlank() && record.getString("source_id").isNotBlank())
   val start=java.time.Instant.parse(record.getString("start"));val end=java.time.Instant.parse(record.getString("end"));require(end>=start)
   when(record.getString("kind")) {
    "exercise"->record.getInt("exercise_type")
    "weight"->require(record.getDouble("weight_lb").let{it.isFinite() && it>0})
    "heart_rate"->{val samples=record.getJSONArray("samples");for(i in 0 until samples.length()) {val sample=samples.getJSONObject(i);java.time.Instant.parse(sample.getString("time"));require(sample.getLong("bpm") in 1..400)}}
   }
  }
  for(key in links.keys())require(records.optJSONObject(links.getString(key))?.optString("kind")=="exercise"){"Missing tracker link"}
  return state
 }
}
