package com.gwensmuda.euros

import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate

/** Cache only exact input snapshots; cached prose never stands in for changed records. */
object WeeklyReviewCache {
 private const val VERSION=3
 fun canGenerate(week:LocalDate,today:LocalDate)=week<=today
 fun errorMessage(error:Exception):String {
  val safe=setOf("Connect to your server in Settings to continue","Connection authorization failed. Reconnect in Settings","The model isn't configured on your server yet","Too many requests. Try again shortly","The model couldn't finish. Try again","The server is temporarily unavailable. Try again")
  return error.message?.takeIf{it in safe} ?: "Couldn't generate the wrap-up. Try again."
 }
 fun key(week:LocalDate)="weekly_review:$week"
 fun fingerprint(week:LocalDate,records:List<Record>,today:LocalDate):String {
  // Revisions alone do not change the evidence after an offline write is acknowledged.
  val data=org.json.JSONArray()
  BackendLlmClient.weeklySnapshot(week,records).forEach { record ->
   data.put(org.json.JSONArray().put(record.type).put(record.id).put(record.deleted).put(record.payload))
  }
  val input=org.json.JSONArray().put(VERSION).put(week.toString())
   .put(minOf(today,week.plusDays(6)).toString()).put(data).toString()
  return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
 }
 fun validate(response:JSONObject,week:LocalDate?=null):JSONObject {
  if(week!=null && response.has("week_start")) require(response.getString("week_start")==week.toString()){ "Wrong review week" }
  require(response.getString("summary").isNotBlank()){"The review was empty. Try again."}
  val adjustments=response.getJSONArray("adjustments")
  for(i in 0 until adjustments.length()) require(adjustments.get(i) is String && adjustments.getString(i).isNotBlank())
  val coverage=response.getJSONObject("coverage")
  LocalDate.parse(coverage.getString("through_date"))
  listOf("logged_activity_count","unlogged_activity_count","food_days","covered_days").forEach { name ->
   val value=coverage.get(name)
   require(value is Number && value.toDouble().isFinite() && value.toDouble()>=0 && value.toDouble()<=Int.MAX_VALUE.toDouble() && value.toDouble()%1.0==0.0)
  }
  require(coverage.getInt("covered_days")<=7 && coverage.getInt("food_days")<=coverage.getInt("covered_days"))
  if(response.has("generated") && !response.getBoolean("generated")) {
   require(response.isNull("model") && response.isNull("provider"))
  } else require(response.getString("model").isNotBlank() && response.getString("provider").isNotBlank())
  return response
 }
 fun encode(fingerprint:String,response:JSONObject,generatedAt:java.time.Instant=java.time.Instant.now()):String=JSONObject()
  .put("version",VERSION).put("fingerprint",fingerprint).put("generated_at",generatedAt.toString()).put("response",validate(response)).toString()
 fun decode(raw:String?,fingerprint:String,week:LocalDate?=null):JSONObject?=runCatching {
  val saved=JSONObject(raw ?: return null)
  if(saved.getInt("version")!=VERSION || saved.getString("fingerprint")!=fingerprint)return null
  java.time.Instant.parse(saved.getString("generated_at"))
  validate(saved.getJSONObject("response"),week)
 }.getOrNull()
}
