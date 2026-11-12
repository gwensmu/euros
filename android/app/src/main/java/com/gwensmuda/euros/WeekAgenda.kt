package com.gwensmuda.euros

import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** Week needs activity identities/statuses, not seven meal and nutrition calculations. */
data class WeekAgenda(val days:Map<LocalDate,List<Record>>,val children:Map<String,List<Record>>) {
 fun onDay(date:LocalDate)=days[date].orEmpty()
 fun childrenOf(record:Record)=children[record.id].orEmpty()
 fun rowRecords(record:Record)=listOf(record)+childrenOf(record)
 companion object {
  fun project(records:List<Record>,week:LocalDate,zone:ZoneId=PlanDomain.zone):WeekAgenda {
   data class Activity(val record:Record,val parent:String,val scheduled:String)
   val activities=records.asSequence().filter{it.type=="activities" && !it.deleted}.map {
    val payload=JSONObject(it.payload)
    Activity(it,payload.optString("parent_id"),payload.getString("scheduled_at"))
   }.sortedBy{it.scheduled}.toList()
   val days=linkedMapOf<LocalDate,MutableList<Record>>()
   val children=linkedMapOf<String,MutableList<Record>>()
   for(activity in activities) {
    if(activity.parent.isNotBlank())children.getOrPut(activity.parent){mutableListOf()}.add(activity.record)
    else {
     val date=OffsetDateTime.parse(activity.scheduled).atZoneSameInstant(zone).toLocalDate()
     if(date>=week && date<week.plusDays(7))days.getOrPut(date){mutableListOf()}.add(activity.record)
    }
   }
   return WeekAgenda(days,children)
  }
 }
}
