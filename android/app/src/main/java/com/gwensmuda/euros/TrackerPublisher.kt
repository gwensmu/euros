package com.gwensmuda.euros

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Explicit Polar source snapshots only. Missing local state never implies a shared deletion. */
object PolarProjection {
 fun workouts(state:JSONObject,activeActivityIds:Set<String>?=null):List<JSONObject> {
  val records=state.optJSONObject("records") ?: return emptyList()
  val links=state.optJSONObject("links") ?: JSONObject()
  val source=records.keys().asSequence().sorted().map{it to records.getJSONObject(it)}.toList()
  return source.filter{(_,r)->r.optString("kind")=="exercise" && r.optString("source")==HealthSources.POLAR}.map { (key,session)->
   val start=Instant.parse(session.getString("start"));val end=Instant.parse(session.getString("end"))
   val deleted=session.optBoolean("deleted")
   val linked=links.keys().asSequence().sorted().firstOrNull{links.optString(it)==key && (activeActivityIds==null || it in activeActivityIds)}
   val samples=linkedMapOf<Instant,JSONObject>()
   if(!deleted) source.filter{(_,r)->r.optString("kind")=="heart_rate" && r.optString("source")==HealthSources.POLAR && !r.optBoolean("deleted")}
    .sortedWith(compareBy<Pair<String,JSONObject>>{it.second.optString("modified_at")}.thenBy{it.first}).forEach { (_,r)->
     val values=r.getJSONArray("samples")
     for(i in 0 until values.length()) {
      val sample=values.getJSONObject(i);val at=Instant.parse(sample.getString("time"))
      if(at>=start && at<=end)samples[at]=JSONObject().put("at",at.toString()).put("bpm",sample.getLong("bpm"))
       .put("source_id",r.getString("source_id")).put("source_package",r.getString("source"))
     }
    }
   require(samples.size<=20000){"A Polar workout exceeds the upload sample limit"}
   JSONObject().put("source_key",key).put("source_package",session.getString("source"))
    .put("source_id",session.getString("source_id")).put("source_modified_at",session.getString("modified_at"))
    .put("deleted",deleted).put("start_at",start.toString()).put("end_at",end.toString())
    .put("exercise_type",session.getInt("exercise_type"))
    .put("activity_id",linked ?: JSONObject.NULL)
    .put("source_title",session.optString("title").takeIf{it.isNotBlank() && it!="null"} ?: JSONObject.NULL)
    .put("heart_rate_samples",JSONArray().apply{samples.toSortedMap().values.forEach{put(it)}})
  }
 }
 fun hash(payload:JSONObject):String=MessageDigest.getInstance("SHA-256").digest(payload.toString().toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
}

/** Durable envelopes are retried byte-for-byte; checkpoints advance only after matching acknowledgement. */
class TrackerPublicationConflict:Exception("Workout changes conflict with the server")

class TrackerPublisher(private val dao:EurosDao) {
 companion object {
  const val CHECKPOINT="polar_publish_checkpoints_v1"
  const val PENDING="polar_publish_pending_v1"
  private val mutex=Mutex()
  private const val MAX_BYTES=3_900_000
 }
 suspend fun publish(api:SyncApi)=mutex.withLock {
  suspend fun send(queued:JSONObject) {
   if(queued.optBoolean("blocked"))throw TrackerPublicationConflict()
   val request=queued.getJSONObject("request")
   val response=try{api.request("/v1/tracker/import",request)}catch(conflict:RevisionConflict) {
    queued.put("blocked",true)
    dao.meta(Metadata(PENDING,HealthSnapshotStorage.encode(queued)))
    throw TrackerPublicationConflict()
   }catch(failure:ApiFailure) {
    if(failure.status !in setOf(400,404,422))throw failure
    queued.put("blocked",true)
    dao.meta(Metadata(PENDING,HealthSnapshotStorage.encode(queued)))
    throw TrackerPublicationConflict()
   }
   check(response.getString("command_id")==request.getString("command_id")){"Invalid tracker acknowledgement"}
   val acknowledgements=response.getJSONArray("records")
   val commands=request.getJSONArray("records")
   check(acknowledgements.length()==commands.length()){"Incomplete tracker acknowledgement"}
   val accepted=(0 until acknowledgements.length()).map{acknowledgements.getJSONObject(it)}.associateBy{it.getString("source_key")}
   check(accepted.size==commands.length()){"Duplicate tracker acknowledgement"}
   val checkpoint=dao.meta(CHECKPOINT)?.let(::JSONObject) ?: JSONObject()
   for(i in 0 until commands.length()) {
    val command=commands.getJSONObject(i);val key=command.getString("source_key")
    val ack=accepted[key] ?: error("Missing tracker acknowledgement")
    check(ack.getLong("revision")==command.getLong("expected_revision")+1 && ack.getBoolean("deleted")==command.getBoolean("deleted")){"Invalid tracker revision"}
    checkpoint.put(key,JSONObject().put("revision",ack.getLong("revision")).put("hash",queued.getJSONObject("hashes").getString(key)))
   }
   // Crash between these writes is safe: the retained envelope has a server idempotency key.
   dao.meta(Metadata(CHECKPOINT,checkpoint.toString()))
   dao.removeMeta(PENDING)
  }
  dao.meta(PENDING)?.let{send(HealthSnapshotStorage.decode(it))}
  val local=dao.meta(HealthImport.KEY)?.let{HealthSnapshotStorage.decode(it)} ?: return@withLock
  val checkpoints=dao.meta(CHECKPOINT)?.let(::JSONObject) ?: JSONObject()
  val activeIds=dao.recordsIncludingDeleted().filter{it.type=="activities" && !it.deleted}.map{it.id}.toSet()
  val changed=PolarProjection.workouts(local,activeIds).filter { payload->
   val checkpoint=checkpoints.optJSONObject(payload.getString("source_key"))
   !(payload.getBoolean("deleted") && checkpoint==null) && checkpoint?.optString("hash")!=PolarProjection.hash(payload)
  }.sortedBy{!it.isNull("activity_id")}
  var batch=JSONArray();var hashes=JSONObject()
  suspend fun flush() {
   if(batch.length()==0)return
   val queued=JSONObject().put("request",JSONObject().put("command_id",UUID.randomUUID().toString()).put("records",batch)).put("hashes",hashes)
   dao.meta(Metadata(PENDING,HealthSnapshotStorage.encode(queued)))
   send(queued);batch=JSONArray();hashes=JSONObject()
  }
  var sendingLinked=false
  for(payload in changed) {
   if(!payload.isNull("activity_id") && !sendingLinked){flush();sendingLinked=true}
   val key=payload.getString("source_key");val hash=PolarProjection.hash(payload)
   val command=JSONObject(payload.toString()).put("expected_revision",checkpoints.optJSONObject(key)?.optLong("revision") ?: 0)
    .put("observed_at",listOfNotNull(
     local.optString("refreshed_at").takeIf{it.isNotBlank()},
     local.optJSONObject("publication_observed_at")?.optString(key)?.takeIf{it.isNotBlank()},
     payload.optString("source_modified_at").takeIf{it.isNotBlank()},
    ).map(Instant::parse).maxOrNull()?.toString() ?: error("Missing source observation time"))
   val bytes=command.toString().toByteArray(Charsets.UTF_8).size
   require(bytes<MAX_BYTES){"A Polar workout exceeds the upload size limit"}
   if(batch.length()>=20 || batch.toString().toByteArray(Charsets.UTF_8).size+bytes>MAX_BYTES)flush()
   batch.put(command);hashes.put(key,hash)
  }
  flush()
 }
}
