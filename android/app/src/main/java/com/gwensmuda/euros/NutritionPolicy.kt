package com.gwensmuda.euros

import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate

object NutritionPolicy {
 fun maintenanceStart(plan:JSONObject,records:List<Record>,today:LocalDate=LocalDate.now(PlanDomain.zone)):LocalDate? {
  val goal=plan.optJSONObject("goal") ?: return null
  if(goal.optString("transition")!="logged_weight_at_or_below")return null
  val threshold=goal.optString("selectedGoalLb").toBigDecimalOrNull() ?: return null
  val enabled=runCatching{LocalDate.parse(goal.getString("transitionEnabledFrom"))}.getOrNull() ?: return null
  return records.filter{it.type=="weight_entries" && !it.deleted}.mapNotNull { record -> runCatching {
   val p=JSONObject(record.payload);val date=LocalDate.parse(p.getString("local_date"))
   val weight=BigDecimal(p.getString("weight_lb"))
   date.takeIf{it>=enabled && it<=today && weight>BigDecimal.ZERO && weight<=threshold}
  }.getOrNull() }.minOrNull()
 }
 fun targets(plan:JSONObject,records:List<Record>,date:LocalDate,today:LocalDate=LocalDate.now(PlanDomain.zone)):JSONObject? {
  val nutrition=plan.optJSONObject("nutrition")
  val start=maintenanceStart(plan,records,today)
  if(start!=null && date>=start)return nutrition?.optJSONObject("maintenance")
  return FoodDomain.savedMealPlan(records,date)?.optJSONObject("nutrition_targets") ?: nutrition?.optJSONObject("daily")
 }
}
