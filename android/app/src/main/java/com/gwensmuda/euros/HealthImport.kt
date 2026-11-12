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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** Imported values stay source-owned; selected Polar snapshots may be published. Linking never completes a planned activity. */
data class ImportedSession(val sourceKey:String,val sourcePackage:String,val start:Instant,val end:Instant,val exerciseType:Int,val deleted:Boolean=false)
data class ImportedWeight(val sourceKey:String,val sourcePackage:String,val recordedAt:Instant,val weightLb:Double)
data class HeartSample(val time:Instant,val bpm:Long)
data class SessionRecap(val session:ImportedSession,val samples:List<HeartSample>,val coveredSeconds:Double,val averageBpm:Double?,val peakBpm:Long?,val zones:List<Double>)
data class MovementSummary(val workoutWalkMinutes:Long?,val walkCount:Int,val sourcePackage:String=HealthSources.FITBIT)
data class HealthImportSummary(val sessions:Int,val heartRateRecords:Int,val messages:List<String>,val weightRecords:Int=0)
class HealthImport(private val context:Context,private val dao:EurosDao) {
 companion object { const val KEY="health_import_v1";private val mutex=Mutex() }
 private val reads=(context.applicationContext as? EurosApplication)?.takeIf{it.repository.dao===dao}?.healthReads ?: HealthReadCache(dao)
 private suspend fun state(samples:HealthSnapshotStorage.Samples=HealthSnapshotStorage.Samples.ALL)=withContext(Dispatchers.Default){dao.meta(KEY)?.let{HealthSnapshotStorage.decode(it,samples)} ?: JSONObject().put("schema",1).put("records",JSONObject()).put("links",JSONObject())}
 suspend fun refresh(days:Int=7):HealthImportSummary {
  require(days in 1..30)
  val end=Instant.now();return refreshFrom(end.minus(Duration.ofDays(days.toLong())),end)
 }
 suspend fun refreshFrom(start:Instant,now:Instant=Instant.now()):HealthImportSummary=withContext(Dispatchers.Default){mutex.withLock {
  val end=now;require(start<end){"Import start must precede now"}
  require(Duration.between(start,end)<=Duration.ofDays(30)){"This import supports the last 30 days"}
  check(HealthConnectClient.getSdkStatus(context)==HealthConnectClient.SDK_AVAILABLE){"Health Connect unavailable"}
  val client=HealthConnectClient.getOrCreate(context);val granted=client.permissionController.getGrantedPermissions()
  val current=state();val messages=mutableListOf<String>()
  suspend fun <T:HealthRecord> read(type:KClass<T>,kind:String,readStart:Instant=start,encode:(T)->JSONObject) {
   if(HealthPermission.getReadPermission(type) !in granted) { messages.add("$kind access not granted");return }
   val snapshot=mutableListOf<JSONObject>();var token:String?=null;var pages=0
   do {
    val page=client.readRecords(ReadRecordsRequest(type,TimeRangeFilter.between(readStart,end),pageSize=1000,pageToken=token))
    page.records.forEach{snapshot.add(encode(it).put("kind",kind).put("source",it.metadata.dataOrigin.packageName).put("source_id",it.metadata.id).put("modified_at",it.metadata.lastModifiedTime.toString()).put("deleted",false))}
    token=page.pageToken;pages++
   } while(token!=null && pages<20)
   // Never reconcile a truncated snapshot: absence would not prove deletion.
   check(token==null){"Too many $kind records; use a smaller import window"}
   HealthSnapshots.reconcile(current.getJSONObject("records"),kind,readStart,end,snapshot)
  }
  read(ExerciseSessionRecord::class,"exercise") { r -> JSONObject().put("start",r.startTime.toString()).put("end",r.endTime.toString()).put("start_offset",r.startZoneOffset?.toString() ?: JSONObject.NULL).put("end_offset",r.endZoneOffset?.toString() ?: JSONObject.NULL).put("exercise_type",r.exerciseType).put("title",r.title ?: JSONObject.NULL) }
  read(HeartRateRecord::class,"heart_rate") { r -> JSONObject().put("start",r.startTime.toString()).put("end",r.endTime.toString()).put("samples",JSONArray().apply{r.samples.forEach{put(JSONObject().put("time",it.time.toString()).put("bpm",it.beatsPerMinute))}}) }
  read(WeightRecord::class,"weight",end.minus(Duration.ofDays(30))) { r -> JSONObject().put("start",r.time.toString()).put("end",r.time.toString()).put("weight_lb",r.weight.inPounds).put("original_kg",r.weight.inKilograms).put("zone_offset",r.zoneOffset?.toString() ?: JSONObject.NULL) }
  applyMatches(current)
  current.put("refreshed_at",end.toString());dao.meta(Metadata(KEY,HealthSnapshotStorage.encodeHealth(current)))
  // Explicitly observed scale measurements, never profile weights. Preserve manual overrides/tombstones.
  val repo=(context.applicationContext as? EurosApplication)?.repository
  if(repo!=null) {
   val observed=current.getJSONObject("records")
   for(key in observed.keys()) {
    val r=observed.getJSONObject(key)
    if(r.optString("kind")!="weight")continue
    val existing=dao.recordsIncludingDeleted().firstOrNull{it.type=="weight_entries" && JSONObject(it.payload).optString("source_key")==key}
    if(existing?.deleted==true || existing?.let{JSONObject(it.payload).optBoolean("manual_override")}==true)continue
    if(r.optBoolean("deleted")) {
     if(existing!=null && JSONObject(existing.payload).optBoolean("auto_imported"))repo.delete(existing.type,existing.id,expectedPayload=existing.payload)
    } else if(HealthSources.isObservedWeight(r) && !Instant.parse(r.getString("start")).isAfter(end)) {
     val at=Instant.parse(r.getString("start")).atZone(PlanDomain.canonicalZone).toOffsetDateTime()
     if(at.toLocalDate()<LocalDate.parse("2026-10-22"))continue
     val payload=PlanDomain.weightPayload(java.math.BigDecimal.valueOf(r.getDouble("weight_lb")).setScale(3,java.math.RoundingMode.HALF_UP).toPlainString(),at,"tracker",PlanDomain.canonicalZone)
      .put("source_key",key).put("source_weight_lb",r.get("weight_lb")).put("original_kg",r.get("original_kg")).put("auto_imported",true)
     if(existing!=null && !JSONObject(existing.payload).optBoolean("auto_imported"))continue
     if(existing==null || existing.payload!=payload.toString())repo.saveWeight(existing?.id ?: "scale-"+java.util.UUID.nameUUIDFromBytes(key.toByteArray()),payload,expectedPayload=existing?.payload)
    }
   }
  }
  requestPublish()
  val records=current.getJSONObject("records");val live=records.keys().asSequence().map{records.getJSONObject(it)}.filter{!it.optBoolean("deleted")}.toList()
  HealthImportSummary(live.count{it.getString("kind")=="exercise"},live.count{it.getString("kind")=="heart_rate"},messages,live.count{it.getString("kind")=="weight"})
 }}
 private suspend fun applyMatches(s:JSONObject):Boolean {
  val activities=dao.recordsIncludingDeleted()
  val deleted=activities.filter{it.type=="activities" && it.deleted}.map{it.id}.toSet()
  val links=s.getJSONObject("links")
  val removed=links.keys().asSequence().filter{it in deleted}.toList()
  removed.forEach{id->markLinkObservation(s,links.getString(id));links.remove(id)}
  val additions=SessionLinking.proposals(activities,s)
  additions.forEach{(id,key)->HealthSnapshots.link(links,id,key);markLinkObservation(s,key)}
  return additions.isNotEmpty() || removed.isNotEmpty()
 }
 suspend fun reconcileLinks()=withContext(Dispatchers.Default){mutex.withLock {
  val s=state()
  if(applyMatches(s)){dao.meta(Metadata(KEY,HealthSnapshotStorage.encodeHealth(s)));requestPublish()}
 }}
 suspend fun candidates(activity:Record)=reads.candidates(activity)
 suspend fun link(activityId:String,sourceKey:String)=withContext(Dispatchers.Default){mutex.withLock {
  val activity=dao.record("activities",activityId);check(activity!=null && !activity.deleted && JSONObject(activity.payload).optString("kind") in setOf("bjj","weights")){"Training activity unavailable"}
  val s=state();val record=s.getJSONObject("records").optJSONObject(sourceKey)
  check(record!=null && HealthSources.isTraining(record)){"Imported session unavailable"}
  val previous=s.getJSONObject("links").optString(activityId).takeIf{it.isNotBlank()}
  HealthSnapshots.link(s.getJSONObject("links"),activityId,sourceKey)
  markLinkObservation(s,sourceKey);previous?.takeIf{it!=sourceKey}?.let{SessionLinking.exclude(s,activityId,it);markLinkObservation(s,it)}
  dao.meta(Metadata(KEY,HealthSnapshotStorage.encodeHealth(s)));requestPublish()
 }}
 suspend fun unlink(activityId:String)=withContext(Dispatchers.Default){mutex.withLock { val s=state();s.getJSONObject("links").optString(activityId).takeIf{it.isNotBlank()}?.let{SessionLinking.exclude(s,activityId,it);markLinkObservation(s,it)};s.getJSONObject("links").remove(activityId);dao.meta(Metadata(KEY,HealthSnapshotStorage.encodeHealth(s)));requestPublish() }}
 suspend fun recap(activityId:String)=reads.recap(activityId)
 suspend fun recapForKey(sourceKey:String)=reads.recapForKey(sourceKey)
 suspend fun sessions(startDate:LocalDate,endDate:LocalDate)=reads.sessions(startDate,endDate)
 suspend fun refreshedAt()=reads.refreshedAt()
 suspend fun linkedActivities()=reads.linkedActivities()
 suspend fun sourceCounts()=reads.sourceCounts()
 suspend fun movement(date:LocalDate)=reads.movement(date)
 suspend fun weightCandidates()=reads.weightCandidates()
 private fun markLinkObservation(state:JSONObject,key:String) {
  val times=state.optJSONObject("publication_observed_at") ?: JSONObject().also{state.put("publication_observed_at",it)}
  times.put(key,Instant.now().toString())
 }
 private fun requestPublish(){(context.applicationContext as? EurosApplication)?.connection?.request()}
 suspend fun clearImports()=withContext(Dispatchers.Default){mutex.withLock { dao.removeMeta(KEY) }}
 private fun session(key:String,r:JSONObject)=HealthRecaps.session(key,r)
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
 enum class Samples { ALL, POLAR, NONE }
 fun encodeHealth(state:JSONObject)=encode(state,16_000_000)
 fun encode(state:JSONObject,maxEncodedSize:Int=1_500_000,maxDecodedBytes:Long=50_000_000):String {
  val buffer=java.io.ByteArrayOutputStream()
  val compressed=java.util.zip.GZIPOutputStream(buffer)
  val bounded=object:java.io.FilterOutputStream(compressed) {
   var count=0L
   private fun reserve(n:Int){count+=n;require(count<=maxDecodedBytes){"Imported snapshot exceeds local limit"}}
   override fun write(value:Int){reserve(1);out.write(value)}
   override fun write(bytes:ByteArray,offset:Int,length:Int){reserve(length);out.write(bytes,offset,length)}
  }
  com.google.gson.stream.JsonWriter(java.io.OutputStreamWriter(bounded,Charsets.UTF_8)).use { write(it,state) }
  val encoded=java.util.Base64.getEncoder().encodeToString(buffer.toByteArray())
  require(encoded.length<maxEncodedSize){"Imported history is too large; use a smaller time window"}
  return JSONObject().put("encoding","gzip-base64").put("data",encoded).toString()
 }
 private fun write(writer:com.google.gson.stream.JsonWriter,value:Any?) {
  when(value) {
   is JSONObject->{writer.beginObject();value.keys().forEach{writer.name(it);write(writer,value.get(it))};writer.endObject()}
   is JSONArray->{writer.beginArray();for(i in 0 until value.length())write(writer,value.get(i));writer.endArray()}
   is Number->writer.value(value)
   is Boolean->writer.value(value)
   null,JSONObject.NULL->writer.nullValue()
   else->writer.value(value.toString())
  }
 }
 /** Stream decompression: never allocate a second uncompressed history string.
  * Read-only projections skip sample arrays before allocating their objects. */
 fun decode(raw:String,samples:Samples=Samples.ALL):JSONObject {
  // The compressed envelope is small. Legacy JSON is streamed directly.
  val sampleKeys=if(samples==Samples.POLAR) {
   val records=decode(raw,Samples.NONE).optJSONObject("records") ?: JSONObject()
   records.keys().asSequence().filter{records.getJSONObject(it).optString("source")==HealthSources.POLAR}.toSet()
  } else emptySet()
  return decodeWithKeys(raw,samples,sampleKeys)
 }
 fun decodeSelected(raw:String,sampleKeys:Set<String>):JSONObject=decodeWithKeys(raw,Samples.POLAR,sampleKeys)
 fun decodeRecords(raw:String,recordKeys:Set<String>):JSONObject=decodeWithKeys(raw,Samples.POLAR,recordKeys,recordKeys)
 private fun decodeWithKeys(raw:String,samples:Samples,sampleKeys:Set<String>,recordKeys:Set<String>?=null):JSONObject {
  val envelope=if(raw.contains("\"encoding\"") && raw.contains("\"gzip-base64\"")) JSONObject(raw) else null
  val input=if(envelope!=null) java.util.zip.GZIPInputStream(java.util.Base64.getDecoder().decode(envelope.getString("data")).inputStream()) else raw.byteInputStream()
  val bounded=object:java.io.FilterInputStream(input) {
   var count=0L
   private fun checked(n:Int):Int {if(n>0){count+=n;require(count<=50_000_000){"Imported snapshot exceeds local limit"}};return n}
   override fun read():Int {val n=super.read();if(n>=0)checked(1);return n}
   override fun read(b:ByteArray,off:Int,len:Int)=checked(`in`.read(b,off,len))
  }
  return com.google.gson.stream.JsonReader(java.io.InputStreamReader(bounded,Charsets.UTF_8)).use { read(it,samples,sampleKeys,recordKeys=recordKeys) as JSONObject }
 }
 private fun read(reader:com.google.gson.stream.JsonReader,samples:Samples,sampleKeys:Set<String> = emptySet(),recordMap:Boolean=false,recordKeys:Set<String>?=null):Any {
  return when(reader.peek()) {
   com.google.gson.stream.JsonToken.BEGIN_OBJECT->{
    val result=JSONObject();reader.beginObject()
    while(reader.hasNext()) {
     val name=reader.nextName()
     if(recordMap && recordKeys!=null && name !in recordKeys)reader.skipValue()
     else if(name=="samples" && samples==Samples.NONE) reader.skipValue()
     else if(recordMap) result.put(name,read(reader,if(name in sampleKeys) Samples.ALL else Samples.NONE,recordKeys=recordKeys))
     else result.put(name,read(reader,samples,sampleKeys,name=="records" && samples==Samples.POLAR,recordKeys))
    };reader.endObject();result
   }
   com.google.gson.stream.JsonToken.BEGIN_ARRAY->{val result=JSONArray();reader.beginArray();while(reader.hasNext())result.put(read(reader,samples,sampleKeys,recordKeys=recordKeys));reader.endArray();result}
   com.google.gson.stream.JsonToken.STRING->reader.nextString()
   com.google.gson.stream.JsonToken.NUMBER->{val n=reader.nextString();n.toLongOrNull() ?: java.math.BigDecimal(n)}
   com.google.gson.stream.JsonToken.BOOLEAN->reader.nextBoolean()
   com.google.gson.stream.JsonToken.NULL->{reader.nextNull();JSONObject.NULL}
   else->error("Invalid tracker snapshot")
  }
 }
}

/** Pure local projection, also used by the shared Week cache. */
object HealthRecaps {
 fun buildRecap(s:JSONObject,key:String):SessionRecap? {
  val records=s.getJSONObject("records");val raw=records.optJSONObject(key) ?: return null
  if(raw.optString("kind")!="exercise" || raw.optString("source")!=HealthSources.POLAR) return null
  val session=session(key,raw)
  val samples=mutableListOf<HeartSample>()
  if(!session.deleted) records.keys().asSequence().toList().sortedWith(compareBy<String>{records.getJSONObject(it).optString("modified_at")}.thenBy{it}).forEach { id -> val r=records.getJSONObject(id)
   if(r.optString("kind")=="heart_rate" && r.optString("source")==session.sourcePackage && !r.optBoolean("deleted")) {
    val values=r.optJSONArray("samples") ?: return@forEach;for(i in 0 until values.length()) { val item=values.getJSONObject(i);val at=Instant.parse(item.getString("time"));if(at>=session.start && at<=session.end) samples.add(HeartSample(at,item.getLong("bpm"))) }
   }
  }
  return HeartRateZones.recap(session,samples)
 }
 fun session(key:String,r:JSONObject)=ImportedSession(key,r.getString("source"),Instant.parse(r.getString("start")),Instant.parse(r.getString("end")),r.getInt("exercise_type"),r.optBoolean("deleted"))
}
