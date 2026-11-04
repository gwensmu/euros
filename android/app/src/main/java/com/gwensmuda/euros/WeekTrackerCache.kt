package com.gwensmuda.euros

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

/** Retained across tabs; one HR decode per saved snapshot or activity membership change. */
class WeekTrackerCache(source:Flow<String?>,scope:CoroutineScope,activities:Flow<List<Record>?> = flowOf(null)) {
 private val projection=combine(source.distinctUntilChanged(),activities.map{list->list?.filter{it.type=="activities" && !it.deleted}?.map{it.id}?.toSet()}.distinctUntilChanged()) { raw,ids -> projectAll(raw,ids) }
  .flowOn(Dispatchers.Default).stateIn(scope,SharingStarted.Eagerly,WeekTrackerProjection())
 val state=projection.map{it.unlinked}.stateIn(scope,SharingStarted.Eagerly,emptyList())
 val linked=projection.map{it.linked}.stateIn(scope,SharingStarted.Eagerly,emptyMap())
 companion object {
  fun project(raw:String?)=projectAll(raw).unlinked
  fun projectAll(raw:String?,activeIds:Set<String>?=null):WeekTrackerProjection {
   if(raw==null)return WeekTrackerProjection()
   val snapshot=HealthSnapshotStorage.decode(raw,HealthSnapshotStorage.Samples.POLAR)
   val records=snapshot.getJSONObject("records")
   val links=snapshot.getJSONObject("links")
   val owners=links.keys().asSequence().filter{activeIds==null || it in activeIds}.associateBy{links.getString(it)}
   val linked=mutableMapOf<String,WeekTrackerItem>()
   val unlinked=mutableListOf<WeekTrackerItem>()
   records.keys().forEach { key ->
    val record=records.getJSONObject(key)
    if(HealthSources.isTraining(record)) {
     val session=HealthRecaps.session(key,record)
     val item=WeekTrackerItem(session,PlanDisplay.trackerSummary(session,HealthRecaps.buildRecap(snapshot,key)))
     val owner=owners[key]
     if(owner==null)unlinked.add(item) else linked[owner]=item
    }
   }
   return WeekTrackerProjection(unlinked.sortedBy{it.session.start},linked)
  }
 }
}
data class WeekTrackerProjection(val unlinked:List<WeekTrackerItem> = emptyList(),val linked:Map<String,WeekTrackerItem> = emptyMap())
data class WeekTrackerItem(val session:ImportedSession,val label:String)
