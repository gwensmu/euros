package com.gwensmuda.euros

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.*

/** Read-only projections. Mutable import snapshots never share this cache's JSON objects. */
class HealthReadCache(private val dao:EurosDao) {
 private val mutex=Mutex()
 private var initialized=false
 private var source:String?=null
 private var token:String?=null
 private var weights:List<ImportedWeight>?=null
 private var metadata=emptySnapshot()
 private val recaps=object:LinkedHashMap<String,SessionRecap?>(16,.75f,true) {
  override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,SessionRecap?>?)=size>16
 }
 private suspend fun <T> read(block:(String?,JSONObject)->T):T=withContext(Dispatchers.Default) {
  mutex.withLock {
   val version=dao.healthReadSource(if(initialized)token else null)
   val raw=if(version.unchanged)source else version.raw
   if(!initialized || raw!=source) {
    val decoded=raw?.let{HealthSnapshotStorage.decode(it,HealthSnapshotStorage.Samples.NONE)} ?: emptySnapshot()
    source=raw;metadata=decoded;recaps.clear();weights=null;initialized=true
   }
   token=version.token
   block(raw,metadata)
  }
 }
 suspend fun recap(activityId:String):SessionRecap?=read {raw,s->
  s.getJSONObject("links").optString(activityId).takeIf{it.isNotBlank()}?.let{recap(raw,s,it)}
 }
 suspend fun recapForKey(key:String):SessionRecap?=read {raw,s->recap(raw,s,key)}
 private fun recap(raw:String?,s:JSONObject,key:String):SessionRecap? {
  if(recaps.containsKey(key))return recaps[key]
  val records=s.getJSONObject("records");val exercise=records.optJSONObject(key)
  if(raw==null || exercise==null || exercise.optString("kind")!="exercise" || exercise.optString("source")!=HealthSources.POLAR)return null
  val session=HealthRecaps.session(key,exercise)
  val keys=if(session.deleted)emptySet() else records.keys().asSequence().filter { id ->
   val r=records.getJSONObject(id)
   r.optString("kind")=="heart_rate" && r.optString("source")==session.sourcePackage && !r.optBoolean("deleted") &&
    runCatching{Instant.parse(r.getString("start"))<=session.end && Instant.parse(r.getString("end"))>=session.start}.getOrDefault(true)
  }.toSet()
  val result=HealthRecaps.buildRecap(HealthSnapshotStorage.decodeRecords(raw,keys+key),key)
  recaps[key]=result
  return result
 }
 suspend fun candidates(activity:Record):List<ImportedSession> = read {_,s->
  val at=OffsetDateTime.parse(JSONObject(activity.payload).getString("scheduled_at")).toInstant()
  val records=s.getJSONObject("records");val links=s.getJSONObject("links")
  val used=links.keys().asSequence().filter{it!=activity.id}.map{links.getString(it)}.toSet()
  records.keys().asSequence().filter{it !in used}.mapNotNull{key->val r=records.getJSONObject(key);if(HealthSources.isTraining(r))HealthRecaps.session(key,r) else null}.filter{HealthSnapshots.isCandidate(at,it.start,it.end)}.sortedBy{Duration.between(at,it.start).abs()}.toList()
 }
 suspend fun sessions(start:LocalDate,end:LocalDate):List<ImportedSession> = read {_,s->
  require(!end.isBefore(start));val records=s.getJSONObject("records")
  records.keys().asSequence().mapNotNull{key->val r=records.getJSONObject(key);if(HealthSources.isTraining(r))HealthRecaps.session(key,r) else null}.filter{val day=it.start.atZone(HealthSources.ZONE).toLocalDate();day>=start && day<=end}.sortedBy{it.start}.toList()
 }
 suspend fun refreshedAt():String=read {_,s->s.optString("refreshed_at")}
 suspend fun linkedActivities():Map<String,String> = read {_,s->val links=s.getJSONObject("links");links.keys().asSequence().associateWith{links.getString(it)}}
 suspend fun sourceCounts():Map<String,Map<String,Int>> = read {_,s->val records=s.getJSONObject("records");records.keys().asSequence().map{records.getJSONObject(it)}.filter{!it.optBoolean("deleted")}.toList().groupBy{it.getString("source")}.mapValues{(_,items)->items.groupingBy{it.getString("kind")}.eachCount()}}
 suspend fun movement(date:LocalDate):MovementSummary = read {_,s->
  val records=s.getJSONObject("records");val from=date.atStartOfDay(HealthSources.ZONE).toInstant();val to=date.plusDays(1).atStartOfDay(HealthSources.ZONE).toInstant()
  val walks=records.keys().asSequence().map{records.getJSONObject(it)}.filter{HealthSources.isWalk(it)}.map{Instant.parse(it.getString("start")) to Instant.parse(it.getString("end"))}.filter{it.first<to && it.second>from}.toList()
  MovementSummary(if(walks.isEmpty())null else HealthSources.unionSeconds(walks.map{maxOf(from,it.first) to minOf(to,it.second)})/60,walks.size)
 }
 suspend fun weightCandidates():List<ImportedWeight> = read {_,s->
  val records=s.getJSONObject("records")
  weights ?: records.keys().asSequence().mapNotNull{key->val r=records.getJSONObject(key);if(HealthSources.isObservedWeight(r))ImportedWeight(key,r.getString("source"),Instant.parse(r.getString("start")),r.getDouble("weight_lb")) else null}.sortedByDescending{it.recordedAt}.toList().also{weights=it}
 }
 private fun emptySnapshot()=JSONObject().put("schema",1).put("records",JSONObject()).put("links",JSONObject())
}
