package com.gwensmuda.euros
object MascotRules {
 fun mood(activities:List<Record>,all:List<Record>):String {
  if(activities.isEmpty())return "neutral"
  val statuses=activities.map{PlanDomain.progress(it,all)}
  return when{statuses.all{it=="done"}->"pleased";statuses.all{it=="skipped"}->"resting";else->"neutral"}
 }
}
