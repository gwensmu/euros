package com.gwensmuda.euros

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

/** Retained across tab navigation. Room remains the durable source; no network reads. */
class WeekTrackerCache(source:Flow<String?>,scope:CoroutineScope) {
 val state=source.distinctUntilChanged().map { project(it) }
  .flowOn(Dispatchers.Default)
  .stateIn(scope,SharingStarted.Eagerly,emptyList())
 companion object {
  fun project(raw:String?):List<WeekTrackerItem> {
   if(raw==null)return emptyList()
   val snapshot=HealthSnapshotStorage.decode(raw)
   val records=snapshot.getJSONObject("records")
   val links=snapshot.getJSONObject("links")
   val linked=links.keys().asSequence().map{links.getString(it)}.toSet()
   return records.keys().asSequence().filter{it !in linked}.mapNotNull { key ->
    val record=records.getJSONObject(key)
    if(!HealthSources.isTraining(record))null else {
     val session=HealthRecaps.session(key,record)
     WeekTrackerItem(session,PlanDisplay.trackerSummary(session,HealthRecaps.buildRecap(snapshot,key)))
    }
   }.sortedBy{it.session.start}.toList()
  }
 }
}
data class WeekTrackerItem(val session:ImportedSession,val label:String)
