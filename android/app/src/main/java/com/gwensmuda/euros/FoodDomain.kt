package com.gwensmuda.euros

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/** User-entered totals are alternatives to item calculations; this slice never invents nutrients. */
data class FoodDraft(
 val id:String,
 val consumedAt:String,
 val text:String="",
 val grams:String="",
 val kcal:String="",
 val protein:String="",
 val carbs:String="",
 val fat:String="",
 val mealReferenceId:String?=null,
 val plannedActivityId:String?=null,
 val originalPayload:String?=null,
 val baseRevision:Long=0,
 val dateInput:String=consumedAt.take(10),
 val timeInput:String=consumedAt.substringAfter('T').take(5),
 val timeZone:String="America/Chicago",
 val interpretation:String?=null,
 val manualEntry:Boolean=false,
 val interpretationText:String?=null,
 val interpretationGrams:String?=null,
 val quantityExplicit:Boolean=grams.isNotBlank(),
) {
 val localDate:String get()=dateInput
}
data class FoodMealReference(val id:String,val title:String,val protein:String?,val carbs:String?,val fat:String?,val time:String?,val appliesWhen:String="every_day",val foods:List<String> = emptyList())
data class NutrientTotal(val known:BigDecimal?,val missingCount:Int,val entryCount:Int) {
 val partial:Boolean get()=missingCount>0
 fun display():String=known?.setScale(0,RoundingMode.HALF_UP)?.toPlainString() ?: "—"
}
data class NutritionTotals(val kcal:NutrientTotal,val protein:NutrientTotal,val carbs:NutrientTotal,val fat:NutrientTotal,val entries:Int)
class FoodDraftError(val fields:Map<String,String>):IllegalArgumentException(fields.values.firstOrNull() ?: "Check the entry")

object FoodDomain {
 private val decimalPattern=Regex("(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")
 fun newDraft(date:LocalDate,clock:Clock=Clock.system(PlanDomain.zone),plannedActivityId:String?=null):FoodDraft {
  val now=java.time.ZonedDateTime.now(clock)
  val actual=date.atTime(now.toLocalTime()).atZone(clock.zone).toOffsetDateTime()
  return FoodDraft(
   id=UUID.randomUUID().toString(),
   consumedAt=actual.toString(),
   plannedActivityId=plannedActivityId,
   timeZone=clock.zone.id,
  )
 }
 fun draftFromRecord(record:Record):FoodDraft {
  require(record.type=="food_entries" && !record.deleted)
  val json=JSONObject(record.payload)
  val nutrients=json.optJSONObject("nutrients") ?: JSONObject()
  return FoodDraft(
   id=record.id,
   consumedAt=json.getString("consumed_at"),
   text=json.optString("raw_text"),
   grams=value(json,"quantity_g"),
   kcal=value(nutrients,"kcal"),
   protein=value(nutrients,"protein_g"),
   carbs=value(nutrients,"carbs_g"),
   fat=value(nutrients,"fat_g"),
   mealReferenceId=nullableString(json,"meal_reference_id"),
   plannedActivityId=nullableString(json,"planned_activity_id"),
   originalPayload=record.payload,
   baseRevision=record.revision,
   timeZone=json.optString("time_zone",PlanDomain.zone.id),
   interpretation=json.optJSONObject("interpretation")?.toString(),
   manualEntry=json.optBoolean("manual_entry",false),
   interpretationText=nullableString(json,"interpretation_text"),
   interpretationGrams=if(json.isNull("interpretation_grams")) null else json.getString("interpretation_grams"),
   quantityExplicit=json.optBoolean("quantity_explicit",json.optJSONObject("interpretation")==null && value(json,"quantity_g").isNotBlank()),
  )
 }

 fun saveDraft(draft:FoodDraft):String=JSONObject().put("schema_version",1).put("id",draft.id).put("consumed_at",draft.consumedAt)
  .put("interpretation",draft.interpretation?.let(::JSONObject) ?: JSONObject.NULL).put("manual_entry",draft.manualEntry).put("quantity_explicit",draft.quantityExplicit).put("interpretation_text",draft.interpretationText ?: JSONObject.NULL).put("interpretation_grams",draft.interpretationGrams ?: JSONObject.NULL)
  .put("text",draft.text).put("grams",draft.grams).put("kcal",draft.kcal).put("protein",draft.protein).put("carbs",draft.carbs).put("fat",draft.fat)
  .put("meal_reference_id",draft.mealReferenceId ?: JSONObject.NULL).put("planned_activity_id",draft.plannedActivityId ?: JSONObject.NULL).put("original_payload",draft.originalPayload ?: JSONObject.NULL).put("base_revision",draft.baseRevision).put("date_input",draft.dateInput).put("time_input",draft.timeInput).put("time_zone",draft.timeZone).toString()
 fun loadDraft(raw:String):FoodDraft {
  val json=JSONObject(raw)
  require(json.getInt("schema_version")==1)
  val consumedAt=json.getString("consumed_at")
  return FoodDraft(
   id=json.getString("id"),
   consumedAt=consumedAt,
   text=json.getString("text"),
   grams=json.getString("grams"),
   kcal=json.getString("kcal"),
   protein=json.getString("protein"),
   carbs=json.getString("carbs"),
   fat=json.getString("fat"),
   mealReferenceId=nullableString(json,"meal_reference_id"),
   plannedActivityId=nullableString(json,"planned_activity_id"),
   originalPayload=nullableString(json,"original_payload"),
   baseRevision=json.optLong("base_revision",0),
   dateInput=json.optString("date_input",consumedAt.take(10)),
   timeInput=json.optString("time_input",consumedAt.substringAfter('T').take(5)),
   timeZone=json.optString("time_zone",PlanDomain.zone.id),
   interpretation=json.optJSONObject("interpretation")?.toString(),
   manualEntry=json.optBoolean("manual_entry",false),
   interpretationText=nullableString(json,"interpretation_text"),
   interpretationGrams=if(json.isNull("interpretation_grams")) null else json.getString("interpretation_grams"),
   quantityExplicit=json.optBoolean("quantity_explicit",json.optJSONObject("interpretation")==null && value(json,"grams").isNotBlank()),
  )
 }

 fun needsInterpretation(draft:FoodDraft):Boolean = !draft.manualEntry && (
  (draft.interpretation!=null && ((draft.interpretationText!=null && draft.text!=draft.interpretationText) || (draft.interpretationGrams!=null && draft.grams!=draft.interpretationGrams))) ||
  (draft.interpretation==null && listOf(draft.kcal,draft.protein,draft.carbs,draft.fat).all{it.isBlank()})
 )

 /** Explicit total weight belongs in inference context; retain the user's original text in the saved entry. */
 fun interpretationInput(draft:FoodDraft):String = if(!draft.quantityExplicit || draft.grams.isBlank()) draft.text else
  "${draft.text}\nTotal weight of this food entry: ${draft.grams} g. Use this total weight when calculating the nutrients."

 /** Model output supplies a review draft, never a persisted consumption record. */
 fun applyInterpretation(draft:FoodDraft,response:JSONObject):FoodDraft {
  val nutrients=response.getJSONObject("nutrients")
  val clarification=nullableString(response,"clarification")
  if(clarification!=null && listOf("kcal","protein_g","carbs_g","fat_g").all{nutrients.isNull(it)})
   throw IllegalArgumentException(clarification)
  val provenance=response.getJSONObject("interpretation")
  require(provenance.optString("provider").isNotBlank() && provenance.optString("model").isNotBlank()) {"Food response is incomplete"}
  fun amount(json:JSONObject,key:String):String {
   require(json.has(key)){"Food response is incomplete"}
   if(json.isNull(key))return ""
   val number=numeric(json,key) ?: throw IllegalArgumentException("Invalid food amount")
   return displayDecimal(number)
  }
  val result=draft.copy(
   grams=if(draft.quantityExplicit && draft.grams.isNotBlank()) draft.grams else amount(response,"quantity_g"),
   kcal=amount(nutrients,"kcal"),protein=amount(nutrients,"protein_g"),
   carbs=amount(nutrients,"carbs_g"),fat=amount(nutrients,"fat_g"),
   interpretation=provenance.toString(),interpretationText=draft.text,
   interpretationGrams=if(draft.quantityExplicit && draft.grams.isNotBlank()) draft.grams else amount(response,"quantity_g"),
  )
  val errors=validate(result)
  if(errors.isNotEmpty())throw FoodDraftError(errors)
  return result
 }

 fun timestamp(draft:FoodDraft):String {
  val original=OffsetDateTime.parse(draft.consumedAt)
  val date=LocalDate.parse(draft.dateInput)
  val time=java.time.LocalTime.parse(draft.timeInput)
  if(date==original.toLocalDate() && time==original.toLocalTime().truncatedTo(java.time.temporal.ChronoUnit.MINUTES)) return original.toString()
  val local=java.time.LocalDateTime.of(date,time)
  val zone=ZoneId.of(draft.timeZone)
  val offsets=zone.rules.getValidOffsets(local)
  require(offsets.isNotEmpty()) { "This local time doesn't exist because the clocks change" }
  require(offsets.size==1) { "This hour occurs twice; choose a time outside the repeated hour" }
  return local.atOffset(offsets.single()).toString()
 }
 fun validate(draft:FoodDraft):Map<String,String> {
  val errors=linkedMapOf<String,String>()
  if(draft.text.isBlank()) errors["text"]="Enter what you ate"
  if(draft.text.length>6000) errors["text"]="Keep this entry under 6,000 characters"
  runCatching{timestamp(draft)}.onFailure{errors["time"]=it.message ?: "Choose a valid date and time"}
  for((key,value) in listOf("grams" to draft.grams,"kcal" to draft.kcal,"protein" to draft.protein,"carbs" to draft.carbs,"fat" to draft.fat)) {
   if(value.isBlank()) continue
   val number=decimal(value)
   if(number==null || number<BigDecimal.ZERO || number>BigDecimal("200000") || (key=="grams" && number.compareTo(BigDecimal.ZERO)==0) || number.stripTrailingZeros().scale()>6) errors[key]=if(key=="grams") "Enter grams above zero" else "Enter zero or a positive amount"
  }
  return errors
 }
 fun payload(draft:FoodDraft):JSONObject {
  val errors=validate(draft);if(errors.isNotEmpty()) throw FoodDraftError(errors)
  val result=draft.originalPayload?.let(::JSONObject) ?: JSONObject()
  // Preserve original provenance/import fields when correcting; the explicit totals are a manual override.
  result.put("consumed_at",timestamp(draft)).put("local_date",LocalDate.parse(draft.dateInput).toString()).put("time_zone",draft.timeZone).put("raw_text",draft.text.trim())
  result.put("quantity_g",jsonDecimal(draft.grams)).put("nutrition_mode","manual_totals")
  val nutrients=JSONObject().put("kcal",jsonDecimal(draft.kcal)).put("protein_g",jsonDecimal(draft.protein)).put("carbs_g",jsonDecimal(draft.carbs)).put("fat_g",jsonDecimal(draft.fat))
  result.put("nutrients",nutrients)
  result.put("meal_reference_id",draft.mealReferenceId ?: JSONObject.NULL).put("planned_activity_id",draft.plannedActivityId ?: JSONObject.NULL)
  draft.interpretation?.let{result.put("interpretation",JSONObject(it))}
  result.put("interpretation_text",draft.interpretationText ?: JSONObject.NULL)
  result.put("interpretation_grams",draft.interpretationGrams ?: JSONObject.NULL)
  result.put("manual_entry",draft.manualEntry).put("quantity_explicit",draft.quantityExplicit)
  result.put("manual_override",true)
  return result
 }
 fun entries(records:List<Record>,date:LocalDate):List<Record> = records.filter{it.type=="food_entries"}
  .groupBy{it.id}.values.map{duplicates->duplicates.maxWith(compareBy<Record>{it.revision}.thenBy{it.deleted})}
  .filter{!it.deleted && JSONObject(it.payload).optString("local_date")==date.toString()}.sortedByDescending{JSONObject(it.payload).optString("consumed_at")}
 fun summary(records:List<Record>,date:LocalDate):NutritionTotals {
  val nutrients=entries(records,date).map { JSONObject(it.payload).optJSONObject("nutrients") }
  fun sum(key:String):NutrientTotal {
   val known=nutrients.map { it?.let { numeric(it,key) } }
   val numbers=known.filterNotNull()
   return NutrientTotal(if(numbers.isEmpty()) null else numbers.fold(BigDecimal.ZERO,BigDecimal::add),known.count{it==null},nutrients.size)
  }
  return NutritionTotals(sum("kcal"),sum("protein_g"),sum("carbs_g"),sum("fat_g"),nutrients.size)
 }
 fun remainingReferences(references:List<FoodMealReference>,records:List<Record>,date:LocalDate,dismissed:Set<String> = emptySet()):List<FoodMealReference> {
  val fulfilled=entries(records,date).mapNotNull{nullableString(JSONObject(it.payload),"meal_reference_id")}.toSet()
  return references.filter{it.id !in fulfilled && it.id !in dismissed}
 }
 fun nextMeal(references:List<FoodMealReference>,records:List<Record>,date:LocalDate,dismissed:Set<String>,now:java.time.LocalTime):FoodMealReference? {
  val remaining=remainingReferences(references,records,date,dismissed)
  fun time(meal:FoodMealReference)=runCatching{java.time.LocalTime.parse(meal.time)}.getOrNull()
  return remaining.filter{time(it)?.let{at->at>=now}==true}.minByOrNull{time(it)!!} ?: remaining.firstOrNull()
 }
 fun savedMealPlan(records:List<Record>,date:LocalDate):JSONObject? {
  val latest=records.filter{it.type=="meal_plans" && it.id=="meal-plan-$date"}
   .maxWithOrNull(compareBy<Record>{it.revision}.thenBy{it.deleted}) ?: return null
  if(latest.deleted)return null
  return JSONObject(latest.payload).takeIf{it.optString("date")==date.toString()}
 }
 fun mealReferences(plan:JSONObject,date:LocalDate?=null,records:List<Record> = emptyList()):List<FoodMealReference> {
  val dated=date?.let{savedMealPlan(records,it)?.optJSONArray("meals")}
  val references=dated ?: plan.optJSONArray("mealTargets") ?: JSONArray()
  return (0 until references.length()).map { index -> val item=references.getJSONObject(index)
   val foods=item.optJSONArray("foods")
   FoodMealReference(
    id=item.getString("id"),title=item.getString("title"),
    protein=targetLabel(item,"proteinG"),carbs=targetLabel(item,"carbohydrateG"),fat=targetLabel(item,"fatG"),
    time=nullableString(item,"localTime"),appliesWhen=if(dated!=null)"every_day" else item.optString("appliesWhen","every_day"),
    foods=if(foods==null) emptyList() else (0 until foods.length()).mapNotNull{index->(foods.opt(index) as? String)?.trim()?.takeIf{it.isNotBlank()}},
   )
  }
 }
 fun targetLabel(targets:JSONObject?,key:String):String? {
  if(targets==null || targets.isNull(key)) return null
  targets.optJSONObject(key)?.let { range ->
   val min=numeric(range,"min");val max=numeric(range,"max")
   return if(min!=null && max!=null) "${displayDecimal(min)}–${displayDecimal(max)}" else null
  }
  return numeric(targets,key)?.let(::displayDecimal)
 }
 fun targetNumber(targets:JSONObject?,key:String):BigDecimal?=targets?.let{target->
  target.optJSONObject(key)?.let{range->numeric(range,"max")} ?: numeric(target,key)
 }
 /** Range targets fill at the upper endpoint while the full range remains visible. */
 fun targetProgress(total:BigDecimal?,target:BigDecimal?):Float =
  if(total==null || target==null || target<=BigDecimal.ZERO)0f
  else total.divide(target,8,RoundingMode.HALF_UP).toFloat().coerceIn(0f,1f)
 fun displayDecimal(number:BigDecimal):String=number.stripTrailingZeros().toPlainString()
 private fun jsonDecimal(text:String):Any=if(text.isBlank()) JSONObject.NULL else displayDecimal(decimal(text)!!)
 private fun decimal(text:String):BigDecimal? {
  val trimmed=text.trim()
  if(!decimalPattern.matches(trimmed)) return null
  return runCatching{BigDecimal(trimmed)}.getOrNull()
 }
 private fun numeric(json:JSONObject,key:String):BigDecimal? {
  if(json.isNull(key)) return null
  val raw=json.get(key)
  val number=if(raw is Number) runCatching{BigDecimal(raw.toString())}.getOrNull() else decimal(raw.toString())
  if(number==null) return null
  return number.takeIf{it>=BigDecimal.ZERO}
 }
 private fun value(json:JSONObject,key:String):String=if(json.isNull(key)) "" else json.get(key).toString()
 private fun nullableString(json:JSONObject,key:String):String?=if(json.isNull(key)) null else json.optString(key).takeIf{it.isNotBlank()}
}
