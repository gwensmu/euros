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
    require(p.optString("kind") in setOf("bjj","weights","food","recovery","morning","weight","rest","prep")) { "Invalid activity" }
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
   "meal_plans" -> {
    val date=p.getString("date")
    require(java.time.LocalDate.parse(date).toString()==date)
    require(p.optString("reference_day").isNotBlank() && p.getString("reference_day").length<=80)
    fun target(value:Any?) {
     if(value==null || value==JSONObject.NULL)return
     if(value is JSONObject) {
      require(value.keys().asSequence().toSet()==setOf("min","max"))
      val min=quantity(value.get("min"));val max=quantity(value.get("max"));require(min<=max && max<=java.math.BigDecimal("200000"))
     } else require(quantity(value)<=java.math.BigDecimal("200000"))
    }
    val meals=p.getJSONArray("meals")
    require(meals.length() in 1..30)
    val ids=mutableSetOf<String>()
    for(i in 0 until meals.length()) {
     val meal=meals.getJSONObject(i)
     require(meal.getString("id").isNotBlank() && meal.getString("id").length<=100 && ids.add(meal.getString("id")))
     require(meal.getString("title").isNotBlank() && meal.getString("title").length<=200)
     require(meal.getString("appliesWhen") in setOf("every_day","bjj_day"))
     if(!meal.isNull("localTime")){val time=meal.getString("localTime");require(time.matches(Regex("[0-9]{2}:[0-9]{2}")));java.time.LocalTime.parse(time)}
     listOf("proteinG","carbohydrateG","fatG").forEach{require(meal.has(it));target(meal.get(it))}
     val foods=meal.getJSONArray("foods");require(foods.length()<=30)
     for(f in 0 until foods.length())require(foods.get(f) is String && foods.getString(f).isNotBlank() && foods.getString(f).length<=1000)
    }
    val targets=p.getJSONObject("nutrition_targets")
    require(targets.keys().asSequence().toSet()==setOf("kcal","proteinG","carbohydrateG","fatG"))
    listOf("kcal","proteinG","carbohydrateG","fatG").forEach{require(targets.has(it));target(targets.get(it))}
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
