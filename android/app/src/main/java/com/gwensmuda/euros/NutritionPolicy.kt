package com.gwensmuda.euros

import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate

object NutritionPolicy {
 val effective=LocalDate.parse("2026-10-22")
 fun maintenanceStart(plan:JSONObject,records:List<Record>,today:LocalDate=LocalDate.now(PlanDomain.zone)):LocalDate? {
  val goal=plan.optJSONObject("goal") ?: return null
  val threshold=goal.optString("selectedGoalLb").toBigDecimalOrNull() ?: return null
  val enabled=LocalDate.parse(goal.getString("transitionEnabledFrom"))
  val daily=linkedMapOf<LocalDate,BigDecimal>()
  records.filter{it.type=="weight_entries" && !it.deleted}.sortedWith(compareBy<Record>{JSONObject(it.payload).optString("measured_at")}.thenBy{it.id}).forEach { r ->
   runCatching { val p=JSONObject(r.payload);val d=LocalDate.parse(p.getString("local_date"));val w=p.getString("weight_lb").toBigDecimal()
    if(d>=enabled && d<=today && w>BigDecimal.ZERO)daily.putIfAbsent(d,w)
   }
  }
  if(today<effective)return daily.filterValues{it<=threshold}.keys.minOrNull()
  return daily.keys.sorted().firstOrNull { end -> val days=(0L..6L).map{end.minusDays(it)}
   days.all{daily.containsKey(it)} && days.map{daily.getValue(it)}.reduce(BigDecimal::add)<=threshold*BigDecimal(7)
  }?.let{maxOf(it,effective)}
 }
 fun targets(plan:JSONObject,records:List<Record>,date:LocalDate,today:LocalDate=LocalDate.now(PlanDomain.zone)):JSONObject? {
  val nutrition=plan.optJSONObject("nutrition") ?: return null
  val saved=FoodDomain.savedMealPlan(records,date)?.optJSONObject("nutrition_targets")
  if(date<effective) {
   val threshold=plan.optJSONObject("goal")?.opt("selectedGoalLb")?.toString()?.toBigDecimalOrNull() ?: BigDecimal("155")
   val active=records.any { r -> r.type=="weight_entries" && !r.deleted && runCatching {val p=JSONObject(r.payload);val d=p.getString("local_date");val w=p.getString("weight_lb").toBigDecimal();d>="2026-10-15" && d<=minOf(today,date).toString() && w>BigDecimal.ZERO && w<=threshold}.getOrDefault(false) }
   return if(active)nutrition.optJSONObject("maintenance") else saved ?: nutrition.optJSONObject("daily")
  }
  val target=JSONObject((saved ?: nutrition.optJSONObject("tiers")?.optJSONObject("recovery") ?: nutrition.optJSONObject("daily") ?: JSONObject()).toString())
  val start=maintenanceStart(plan,records,today)
  if(start!=null && start<=today && date>=start) {
   target.put("kcal",target.get("kcal").toString().toBigDecimal()+BigDecimal(200))
   target.put("carbohydrateG",target.get("carbohydrateG").toString().toBigDecimal()+BigDecimal(50))
  }
  return target
 }
}
