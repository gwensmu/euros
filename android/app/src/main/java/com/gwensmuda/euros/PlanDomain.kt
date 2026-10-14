package com.gwensmuda.euros

import org.json.JSONObject
import java.time.*

object PlanDomain {
 val zone:ZoneId=ZoneId.of("America/Chicago")
 fun payload(record:Record)=JSONObject(record.payload)
 fun day(record:Record):LocalDate=OffsetDateTime.parse(payload(record).getString("scheduled_at")).atZoneSameInstant(zone).toLocalDate()
 fun status(record:Record)=payload(record).optString("status","pending")
 fun children(parent:Record,records:List<Record>)=records.filter{!it.deleted && it.type=="activities" && payload(it).optString("parent_id")==parent.id}.sortedBy{payload(it).optString("scheduled_at")}
 fun rootActivities(records:List<Record>)=records.filter{!it.deleted && it.type=="activities" && payload(it).optString("parent_id").isBlank()}.sortedBy{payload(it).optString("scheduled_at")}
 fun onDay(records:List<Record>,date:LocalDate)=rootActivities(records).filter{day(it)==date}
 fun progress(parent:Record,records:List<Record>):String {
  val steps=children(parent,records)
  if(steps.isEmpty()) return status(parent)
  val done=steps.count{status(it)=="done"};val skipped=steps.count{status(it)=="skipped"}
  return when {done==steps.size->"done";skipped==steps.size->"skipped";done+skipped>0->"partial";else->"pending"}
 }
 fun resolved(parent:Record,records:List<Record>):Boolean {
  val steps=children(parent,records)
  return if(steps.isEmpty()) status(parent) in setOf("done","skipped") else steps.all{status(it) in setOf("done","skipped")}
 }
 fun next(records:List<Record>,date:LocalDate):Record?=onDay(records,date).firstOrNull{!resolved(it,records)}
 fun moveTimestamp(date:String,time:String):String {
  val local=LocalDateTime.of(LocalDate.parse(date),LocalTime.parse(time))
  val offsets=zone.rules.getValidOffsets(local)
  require(offsets.size==1){if(offsets.isEmpty()) "That time doesn't exist because the clocks change" else "Choose a time outside the repeated hour"}
  return local.atOffset(offsets.single()).toString()
 }
 fun weightPayload(value:String,at:OffsetDateTime,context:String="after_waking"):JSONObject {
  val number=value.toBigDecimalOrNull() ?: throw IllegalArgumentException("Enter a weight in lb")
  require(number>java.math.BigDecimal.ZERO && number<=java.math.BigDecimal("1500") && number.scale()<=3){"Enter a valid weight in lb"}
  return JSONObject().put("weight_lb",number.toPlainString()).put("measured_at",at.toString()).put("local_date",at.atZoneSameInstant(zone).toLocalDate().toString()).put("context",context)
 }
}
