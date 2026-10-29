package com.gwensmuda.euros

import org.json.JSONObject
import java.time.LocalDate

/** Typed view of the plan. Record payloads remain immutable historical snapshots. */
enum class PlanItemKind(val wireName:String) {
 PREP("prep"), BJJ("bjj"), WEIGHTS("weights"), REST("rest"), RECOVERY("recovery"), FOOD("food"), MORNING("morning"), WEIGHT("weight"), UNKNOWN("");
 companion object { fun from(value:String)=entries.firstOrNull{it.wireName==value} ?: UNKNOWN }
}
enum class LoggingMode { SESSION, COMPLETION, STEPS, INTAKE, FOOD, WEIGHT, UNSUPPORTED }
data class PlannedItem(val record:Record,val kind:PlanItemKind,val logging:LoggingMode,val steps:List<Record>)
data class DayPlan(val date:LocalDate,val activities:List<PlannedItem>,val meals:List<FoodMealReference>,val nutrition:JSONObject?) {
 val next:PlannedItem? get()=activities.firstOrNull { item ->
  if(item.steps.isEmpty()) PlanDomain.status(item.record) !in setOf("done","skipped")
  else item.steps.any{PlanDomain.status(it) !in setOf("done","skipped")}
 }
}

object DayPlanComposer {
 fun item(record:Record,records:List<Record>):PlannedItem {
  val payload=PlanDomain.payload(record)
  val kind=PlanItemKind.from(payload.optString("kind"))
  val steps=PlanDomain.children(record,records)
  val logging=when {
   steps.isNotEmpty()->LoggingMode.STEPS
   kind==PlanItemKind.RECOVERY && payload.optString("recovery_step_type")=="supplement"->LoggingMode.INTAKE
   else->when(kind) {
    PlanItemKind.BJJ->LoggingMode.SESSION
    PlanItemKind.PREP,PlanItemKind.WEIGHTS,PlanItemKind.REST,PlanItemKind.RECOVERY,PlanItemKind.MORNING->LoggingMode.COMPLETION
    PlanItemKind.FOOD->LoggingMode.FOOD
    PlanItemKind.WEIGHT->LoggingMode.WEIGHT
    PlanItemKind.UNKNOWN->LoggingMode.UNSUPPORTED
   }
  }
  return PlannedItem(record,kind,logging,steps)
 }
 fun inPhase(plan:JSONObject,date:LocalDate):Boolean {
  val phaseId=plan.optJSONObject("schedule")?.optString("phaseId") ?: return false
  val phases=plan.optJSONArray("phases") ?: return false
  return (0 until phases.length()).map{phases.getJSONObject(it)}.any {
   it.getString("id")==phaseId && !date.isBefore(LocalDate.parse(it.getString("start"))) && !date.isAfter(LocalDate.parse(it.getString("end")))
  }
 }
 fun compose(plan:JSONObject,records:List<Record>,date:LocalDate):DayPlan {
  val activities=PlanDomain.onDay(records,date).map{item(it,records)}
  val applies=inPhase(plan,date)
  val savedMeals=FoodDomain.savedMealPlan(records,date)
  val hasBjj=activities.any{it.kind==PlanItemKind.BJJ}
  val meals=if(applies || savedMeals!=null) FoodDomain.mealReferences(plan,date,records).filter { meal ->
   when(meal.appliesWhen) {"every_day"->true;"bjj_day"->hasBjj;else->false}
  } else emptyList()
  val nutrition=NutritionPolicy.targets(plan,records,date)
  return DayPlan(date,activities,meals,nutrition)
 }
}
