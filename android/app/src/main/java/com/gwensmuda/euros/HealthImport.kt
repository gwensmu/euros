package com.gwensmuda.euros

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record as HealthRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

/** Imported values stay local and source-owned. Linking never completes a planned activity. */
data class ImportedSession(val sourceKey:String,val sourcePackage:String,val start:Instant,val end:Instant,val exerciseType:Int,val deleted:Boolean=false)
data class ImportedWeight(val sourceKey:String,val sourcePackage:String,val recordedAt:Instant,val weightLb:Double)
data class HeartSample(val time:Instant,val bpm:Long)
data class SessionRecap(val session:ImportedSession,val samples:List<HeartSample>,val coveredSeconds:Long,val averageBpm:Double?,val peakBpm:Long?)
data class MovementSummary(val workoutWalkMinutes:Long?,val walkCount:Int,val sourcePackage:String=HealthSources.FITBIT)
data class HealthImportSummary(val sessions:Int,val heartRateRecords:Int,val messages:List<String>,val weightRecords:Int=0)
class HealthImport(private val context:Context,private val dao:EurosDao) {
 companion object { const val KEY="health_import_v1";private val mutex=Mutex() }
 private suspend fun state()=dao.meta(KEY)?.let{HealthSnapshotStorage.decode(it)} ?: JSONObject().put("schema",1).put("records",JSONObject()).put("links",JSONObject())
 suspend fun refresh(days:Int=7):HealthImportSummary {
  require(days in 1..30)
  val end=Instant.now();return refreshFrom(end.minus(Duration.ofDays(days.toLong())),end)
 }
 suspend fun refreshFrom(start:Instant,now:Instant=Instant.now()):HealthImportSummary=mutex.withLock {
  val end=now;require(start<end){"Import start must precede now"}
  require(Duration.between(start,end)<=Duration.ofDays(30)){"This import supports the last 30 days"}
  check(HealthConnectClient.getSdkStatus(context)==HealthConnectClient.SDK_AVAILABLE){"Health Connect unavailable"}
  val client=HealthConnectClient.getOrCreate(context);val granted=client.permissionController.getGrantedPermissions()
  val current=state();val messages=mutableListOf<String>()
  suspend fun <T:HealthRecord> read(type:KClass<T>,kind:String,encode:(T)->JSONObject) {
   if(HealthPermission.getReadPermission(type) !in granted) { messages.add("$kind access not granted");return }
   val snapshot=mutableListOf<JSONObject>();var token:String?=null;var pages=0
   do {
    val page=client.readRecords(ReadRecordsRequest(type,TimeRangeFilter.between(start,end),pageSize=1000,pageToken=token))
    page.records.forEach{snapshot.add(encode(it).put("kind",kind).put("source",it.metadata.dataOrigin.packageName).put("source_id",it.metadata.id).put("modified_at",it.metadata.lastModifiedTime.toString()).put("deleted",false))}
    token=page.pageToken;pages++
   } while(token!=null && pages<20)
   // Never reconcile a truncated snapshot: absence would not prove deletion.
   check(token==null){"Too many $kind records; use a smaller import window"}
   HealthSnapshots.reconcile(current.getJSONObject("records"),kind,start,end,snapshot)
  }
  read(ExerciseSessionRecord::class,"exercise") { r -> JSONObject().put("start",r.startTime.toString()).put("end",r.endTime.toString()).put("start_offset",r.startZoneOffset?.toString() ?: JSONObject.NULL).put("end_offset",r.endZoneOffset?.toString() ?: JSONObject.NULL).put("exercise_type",r.exerciseType) }
  read(HeartRateRecord::class,"heart_rate") { r -> JSONObject().put("start",r.startTime.toString()).put("end",r.endTime.toString()).put("samples",JSONArray().apply{r.samples.forEach{put(JSONObject().put("time",it.time.toString()).put("bpm",it.beatsPerMinute))}}) }
  read(WeightRecord::class,"weight") { r -> JSONObject().put("start",r.time.toString()).put("end",r.time.toString()).put("weight_lb",r.weight.inPounds).put("original_kg",r.weight.inKilograms).put("zone_offset",r.zoneOffset?.toString() ?: JSONObject.NULL) }
  current.put("refreshed_at",end.toString());dao.meta(Metadata(KEY,HealthSnapshotStorage.encode(current)))
  val records=current.getJSONObject("records");val live=records.keys().asSequence().map{records.getJSONObject(it)}.filter{!it.optBoolean("deleted")}.toList()
  HealthImportSummary(live.count{it.getString("kind")=="exercise"},live.count{it.getString("kind")=="heart_rate"},messages,live.count{it.getString("kind")=="weight"})
 }
 suspend fun candidates(activity:Record):List<ImportedSession> = mutex.withLock {
  val payload=JSONObject(activity.payload);val at=OffsetDateTime.parse(payload.getString("scheduled_at")).toInstant()
  val s=state();val records=s.getJSONObject("records");val links=s.getJSONObject("links")
  val used=links.keys().asSequence().filter{it!=activity.id}.map{links.getString(it)}.toSet()
  records.keys().asSequence().filter{it !in used}.mapNotNull{key->val r=records.getJSONObject(key);if(HealthSources.isTraining(r)) session(key,r) else null}.filter{HealthSnapshots.isCandidate(at,it.start,it.end)}.sortedBy{Duration.between(at,it.start).abs()}.toList()
 }
 suspend fun link(activityId:String,sourceKey:String)=mutex.withLock {
  val activity=dao.record("activities",activityId);check(activity!=null && !activity.deleted && JSONObject(activity.payload).optString("kind")=="bjj"){"BJJ activity unavailable"}
  val s=state();val record=s.getJSONObject("records").optJSONObject(sourceKey)
  check(record!=null && HealthSources.isTraining(record)){"Imported session unavailable"}
  HealthSnapshots.link(s.getJSONObject("links"),activityId,sourceKey)
  dao.meta(Metadata(KEY,HealthSnapshotStorage.encode(s)))
 }
 suspend fun unlink(activityId:String)=mutex.withLock { val s=state();s.getJSONObject("links").remove(activityId);dao.meta(Metadata(KEY,HealthSnapshotStorage.encode(s))) }
 suspend fun recap(activityId:String):SessionRecap?=mutex.withLock {
  val s=state();val key=s.getJSONObject("links").optString(activityId).takeIf{it.isNotBlank()} ?: return@withLock null
  buildRecap(s,key)
 }
 suspend fun recapForKey(sourceKey:String):SessionRecap?=mutex.withLock {buildRecap(state(),sourceKey)}
 private fun buildRecap(s:JSONObject,key:String):SessionRecap? {
  val records=s.getJSONObject("records");val raw=records.optJSONObject(key) ?: return null
  if(raw.optString("kind")!="exercise" || raw.optString("source")!=HealthSources.POLAR) return null
  val session=session(key,raw)
  val samples=mutableListOf<HeartSample>()
  if(!session.deleted) records.keys().asSequence().toList().sortedWith(compareBy<String>{records.getJSONObject(it).optString("modified_at")}.thenBy{it}).forEach { id -> val r=records.getJSONObject(id)
   if(r.optString("kind")=="heart_rate" && r.optString("source")==session.sourcePackage && !r.optBoolean("deleted")) {
    val values=r.getJSONArray("samples");for(i in 0 until values.length()) { val item=values.getJSONObject(i);val at=Instant.parse(item.getString("time"));if(at>=session.start && at<=session.end) samples.add(HeartSample(at,item.getLong("bpm"))) }
   }
  }
  val unique=HealthSnapshots.deduplicateSamples(samples);val covered=HealthCoverage.coveredSeconds(session.start,session.end,unique.map{it.time})
  var weighted=0.0;unique.zipWithNext().forEach{(a,b)->val gap=Duration.between(a.time,b.time).seconds;if(gap in 1..60) weighted+=a.bpm*gap.toDouble()}
  return SessionRecap(session,unique,covered,if(covered>0) weighted/covered else null,unique.maxOfOrNull{it.bpm})
 }
 suspend fun sessions(startDate:LocalDate,endDate:LocalDate):List<ImportedSession> = mutex.withLock {
  require(!endDate.isBefore(startDate))
  val records=state().getJSONObject("records")
  records.keys().asSequence().mapNotNull{key->val r=records.getJSONObject(key);if(HealthSources.isTraining(r)) session(key,r) else null}.filter{val day=it.start.atZone(HealthSources.ZONE).toLocalDate();!day.isBefore(startDate) && !day.isAfter(endDate)}.sortedBy{it.start}.toList()
 }
 suspend fun linkedActivities():Map<String,String> = mutex.withLock {
  val links=state().getJSONObject("links");links.keys().asSequence().associateWith{links.getString(it)}
 }
 suspend fun sourceCounts():Map<String,Map<String,Int>> = mutex.withLock {
  val records=state().getJSONObject("records")
  records.keys().asSequence().map{records.getJSONObject(it)}.filter{!it.optBoolean("deleted")}.toList().groupBy{it.getString("source")}.mapValues{(_,items)->items.groupingBy{it.getString("kind")}.eachCount()}
 }
 suspend fun movement(date:LocalDate):MovementSummary = mutex.withLock {
  val records=state().getJSONObject("records");val from=date.atStartOfDay(HealthSources.ZONE).toInstant();val to=date.plusDays(1).atStartOfDay(HealthSources.ZONE).toInstant()
  val walks=records.keys().asSequence().map{records.getJSONObject(it)}.filter{HealthSources.isWalk(it)}.map{Instant.parse(it.getString("start")) to Instant.parse(it.getString("end"))}.filter{it.first<to && it.second>from}.toList()
  MovementSummary(if(walks.isEmpty()) null else HealthSources.unionSeconds(walks.map{maxOf(from,it.first) to minOf(to,it.second)})/60,walks.size)
 }
 suspend fun weightCandidates():List<ImportedWeight> = mutex.withLock {
  val records=state().getJSONObject("records")
  records.keys().asSequence().mapNotNull{key->val r=records.getJSONObject(key);if(HealthSources.isObservedWeight(r)) ImportedWeight(key,r.getString("source"),Instant.parse(r.getString("start")),r.getDouble("weight_lb")) else null}.sortedByDescending{it.recordedAt}.toList()
 }
 suspend fun clearImports()=mutex.withLock { dao.removeMeta(KEY) }
 private fun session(key:String,r:JSONObject)=ImportedSession(key,r.getString("source"),Instant.parse(r.getString("start")),Instant.parse(r.getString("end")),r.getInt("exercise_type"),r.optBoolean("deleted"))
}
object HealthSnapshots {
 // Caller orders records oldest correction first. A newer same-source sample replaces its timestamp.
 fun deduplicateSamples(samples:List<HeartSample>)=samples.asReversed().distinctBy{it.time}.sortedBy{it.time}
 fun key(record:JSONObject)=record.getString("kind")+":"+record.getString("source")+":"+record.getString("source_id")
 fun reconcile(records:JSONObject,kind:String,start:Instant,end:Instant,snapshot:List<JSONObject>) {
  val incoming=snapshot.associateBy{key(it)}
  records.keys().asSequence().toList().forEach { id -> val r=records.getJSONObject(id)
   // Only fully covered intervals can be tombstoned by a bounded snapshot. Preserve older history.
   if(r.getString("kind")==kind && Instant.parse(r.getString("start"))>=start && Instant.parse(r.getString("start"))<end && Instant.parse(r.getString("end"))<=end && id !in incoming) r.put("deleted",true)
  }
  incoming.forEach{(id,r)->records.put(id,r)}
 }
 fun isCandidate(scheduled:Instant,start:Instant,end:Instant):Boolean = end>start && start<=scheduled.plus(Duration.ofHours(3)) && end>=scheduled.minus(Duration.ofHours(3))
 fun link(links:JSONObject,activityId:String,sourceKey:String) {
  require(links.keys().asSequence().none{it!=activityId && links.getString(it)==sourceKey}){"This workout is already linked to another activity"}
  links.put(activityId,sourceKey)
 }
}

/** Explicit user source preference: Polar training; Fitbit walking remains daily movement. */
object HealthSources {
 const val POLAR="fi.polar.polarflow"
 const val FITBIT="com.fitbit.FitbitMobile"
 val ZONE:ZoneId=ZoneId.of("America/Chicago")
 fun requestedStart():Instant=LocalDate.of(2026,10,5).atStartOfDay(ZONE).toInstant()
 fun isTraining(r:JSONObject)=r.optString("kind")=="exercise" && r.optString("source")==POLAR && !r.optBoolean("deleted")
 fun isObservedWeight(r:JSONObject)=r.optString("kind")=="weight" && r.optString("source")==FITBIT && !r.optBoolean("deleted")
 fun isWalk(r:JSONObject)=r.optString("kind")=="exercise" && r.optString("source")==FITBIT && !r.optBoolean("deleted") && r.optInt("exercise_type",-1)==ExerciseSessionRecord.EXERCISE_TYPE_WALKING
 fun unionSeconds(intervals:List<Pair<Instant,Instant>>):Long {
  val valid=intervals.filter{it.second>it.first}.sortedBy{it.first};if(valid.isEmpty()) return 0
  var start=valid.first().first;var end=valid.first().second;var seconds=0L
  for((from,to) in valid.drop(1)) { if(from<=end) end=maxOf(end,to) else {seconds+=Duration.between(start,end).seconds;start=from;end=to} }
  return seconds+Duration.between(start,end).seconds
 }
}

/** Compact snapshots keep large HR series below Android's per-row CursorWindow bound. */
object HealthSnapshotStorage {
 fun encode(state:JSONObject):String {
  val buffer=java.io.ByteArrayOutputStream()
  java.util.zip.GZIPOutputStream(buffer).use{it.write(state.toString().toByteArray(Charsets.UTF_8))}
  val encoded=java.util.Base64.getEncoder().encodeToString(buffer.toByteArray())
  require(encoded.length<1_500_000){"Imported history is too large; remove local imports and use a smaller time window"}
  return JSONObject().put("encoding","gzip-base64").put("data",encoded).toString()
 }
 fun decode(raw:String):JSONObject {
  val envelope=JSONObject(raw)
  if(envelope.optString("encoding")!="gzip-base64") return envelope
  val compressed=java.util.Base64.getDecoder().decode(envelope.getString("data"))
  val bytes=java.util.zip.GZIPInputStream(compressed.inputStream()).use{stream->val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);var n=stream.read(buffer)
   while(n!=-1){require(output.size()+n<=50_000_000){"Imported snapshot exceeds local limit"};output.write(buffer,0,n);n=stream.read(buffer)};output.toByteArray()}
  return JSONObject(String(bytes,Charsets.UTF_8))
 }
}
