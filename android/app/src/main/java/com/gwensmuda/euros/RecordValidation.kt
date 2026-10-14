package com.gwensmuda.euros

import org.json.JSONObject

// Immediate local validation; accepted revisions remain authoritative on the backend.
object RecordValidation {
 private val fields=setOf("kcal","protein_g","carbs_g","fat_g")
 fun quantity(value:Any?,positive:Boolean=false):java.math.BigDecimal {
  require(value!=null && value!=JSONObject.NULL && value !is Boolean) { "Enter an amount" }
  val n=runCatching { java.math.BigDecimal(value.toString()) }.getOrElse { throw IllegalArgumentException("Invalid amount") }
  require(n>=java.math.BigDecimal.ZERO && n<=java.math.BigDecimal("1000000000") && n.scale()<=6 && (!positive || n>java.math.BigDecimal.ZERO)) { "Invalid amount" }
  return n
 }
 fun validate(type:String,p:JSONObject) {
  when(type) {
   "activities" -> {
    require(p.optString("title").isNotBlank()) { "Title required" }
    require(p.optString("kind") in setOf("bjj","weights","food","recovery","morning","weight","rest")) { "Invalid activity" }
    java.time.OffsetDateTime.parse(p.getString("scheduled_at"))
    require(p.optString("status") in setOf("pending","done","skipped"))
    val intake=p.optJSONObject("recovery_intake")
    val substance=p.optString("recovery_step_type") in setOf("hydration","supplement")
    if(p.getString("status")=="done" && p.optString("recovery_step_type")=="supplement") require(intake!=null) { "Enter the actual amount" }
    if(intake!=null) {
     require(p.getString("kind")=="recovery" && p.getString("status")=="done")
     quantity(intake.opt("amount"),true)
     when(intake.optString("kind")) {
      "hydration" -> require(intake.optString("unit") in setOf("ml","l","us_fl_oz"))
      "supplement" -> { require(intake.optString("unit")=="mg");require(intake.optString("product").isNotBlank()) }
      else -> error("Invalid intake kind")
     }
     if(substance) require(intake.getString("kind")==p.getString("recovery_step_type"))
    }
   }
   "food_entries","weight_entries" -> {
    val actual=java.time.OffsetDateTime.parse(p.getString(if(type=="food_entries") "consumed_at" else "measured_at"))
    val local=p.getString("local_date")
    require(java.time.LocalDate.parse(local).toString()==local && actual.toLocalDate().toString()==local) { "Date must match the recorded time" }
    if(type=="weight_entries") {
     quantity(p.opt("weight_lb"),true)
     if(p.has("source_key") && !p.isNull("source_key")) require(p.getString("source_key").isNotBlank())
     if(p.has("weekly_anchor") && !p.isNull("weekly_anchor")) {
      val anchor=java.time.LocalDate.parse(p.getString("weekly_anchor"))
      require(anchor.dayOfWeek==java.time.DayOfWeek.MONDAY && !actual.toLocalDate().isBefore(anchor) && !actual.toLocalDate().isAfter(anchor.plusDays(6)))
     }
    }
    else {
     val nutrients=p.getJSONObject("nutrients")
     require(nutrients.keys().asSequence().toSet()==fields)
     fields.forEach { if(!nutrients.isNull(it)) quantity(nutrients.get(it)) }
     require((p.optJSONArray("items")?.length() ?: 0) == 0) { "Use stored totals for this entry" }
    }
   }
   else -> error("Unsupported record type")
  }
 }
}
