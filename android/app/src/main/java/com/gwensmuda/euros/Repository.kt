package com.gwensmuda.euros

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.UUID

// One mutex serializes local edits, authored bootstrap, restores and synchronization.
// Each durable state transition keeps its Room transaction and immutable outbox command.
class Repository(private val db:EurosDatabase, llmClient:LlmClient?=null) {
 private val completionEvents=kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity=1,onBufferOverflow=kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
 val localCompletions:kotlinx.coroutines.flow.SharedFlow<Unit> = completionEvents
 @Volatile private var interpreter:LlmClient?=llmClient
 suspend fun interpretFood(text:String):JSONObject = requireInterpreter().interpretFood(text)
 suspend fun reviewWeek(weekStart:java.time.LocalDate,records:List<Record>):JSONObject = requireInterpreter().reviewWeek(weekStart,records)
 private fun requireInterpreter():LlmClient = interpreter ?: error("Connect to your server in Settings to continue")
 fun connectInterpretation(api:SyncApi) { interpreter=BackendLlmClient(api) }
 fun disconnectInterpretation() { interpreter=null }

 val dao=db.dao(); private val mutex=Mutex()
 suspend fun bootstrapAuthoredSchedule(context:Context):Int {
  val raw=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
   context.assets.open("schedule-commands.json").bufferedReader().use { it.readText() }
  }
  return bootstrapAuthoredCommands(org.json.JSONArray(raw))
 }
 // Only explicitly authored dated activities are bundled. Replays never resurrect or overwrite records.
 suspend fun bootstrapAuthoredCommands(commands:org.json.JSONArray):Int=mutex.withLock {
  db.withTransaction {
   var inserted=0
   val authored=linkedMapOf<String,JSONObject>()
   for(index in 0 until commands.length()) {
    val command=commands.getJSONObject(index)
    require(command.getString("entity_type")=="activities" && command.getString("operation")=="upsert" && command.getLong("expected_revision")==0L)
    val id=command.getString("entity_id");val payload=command.getJSONObject("payload")
    require(id.isNotBlank() && command.getString("command_id").isNotBlank())
    require(payload.getString("title").isNotBlank() && payload.getString("kind").isNotBlank() && payload.getString("status")=="pending")
    java.time.OffsetDateTime.parse(payload.getString("scheduled_at"))
    require(authored.put(id,command)==null){"Duplicate authored activity"}
   }
   // Validate and sort the entire graph before writing, including already cached nodes.
   val ordered=mutableListOf<String>();val visiting=mutableSetOf<String>();val visited=mutableSetOf<String>()
   fun visit(id:String) {
    if(id in visited)return
    require(visiting.add(id)){"Cyclic authored parent relationship"}
    val command=authored[id] ?: throw IllegalArgumentException("Missing authored parent: $id")
    command.getJSONObject("payload").optString("parent_id").takeIf{it.isNotBlank()}?.let{visit(it)}
    visiting.remove(id);visited.add(id);ordered.add(id)
   }
   authored.keys.forEach{visit(it)}
   val suppressed=mutableSetOf<String>()
   for(id in ordered) {
    val command=authored.getValue(id)
    val payload=JSONObject(command.getJSONObject("payload").toString())
    val parentId=payload.optString("parent_id").takeIf{it.isNotBlank()}
    val parent=parentId?.let{dao.record("activities",it)}
    if(parentId!=null && (parentId in suppressed || parent?.deleted==true)) {suppressed.add(id);continue}
    if(dao.record("activities",id)!=null || dao.pendingFor("activities",id)!=null) continue
    if(parentId!=null) {
     check(parent!=null){"Authored parent unavailable: $parentId"}
     val originalParent=java.time.OffsetDateTime.parse(authored.getValue(parentId).getJSONObject("payload").getString("scheduled_at"))
     val originalChild=java.time.OffsetDateTime.parse(payload.getString("scheduled_at"))
     val currentParent=java.time.OffsetDateTime.parse(JSONObject(parent.payload).getString("scheduled_at"))
     if(currentParent!=originalParent) {
      val offset=java.time.Duration.between(originalParent.toInstant(),originalChild.toInstant())
      val zone=payload.optString("time_zone").takeIf{it.isNotBlank()}?.let{java.time.ZoneId.of(it)} ?: currentParent.offset
      payload.put("scheduled_at",currentParent.toInstant().plus(offset).atZone(zone).toOffsetDateTime().toString())
     }
    }
    dao.put(Record("activities",id,0,payload.toString()))
    dao.enqueue(Pending(command.getString("command_id"),"activities",id,0,payload.toString()))
    inserted++
   }
   inserted
  }
 }
 // Every local edit appends an immutable command; retries never change a command's bytes.
 private suspend fun enqueue(type:String,id:String,payload:JSONObject?,delete:Boolean=false) {
  require(type in setOf("activities","food_entries","weight_entries","meal_plans") && id.isNotBlank() && id.length<=200)
  val current=dao.record(type,id)
  val queued=dao.pendingForEntity(type,id)
  check(queued.none{it.error!=null}) { "Resolve the server conflict before editing this record" }
  if(delete && current?.deleted==true) return
  check(current?.deleted!=true) { "This record was deleted" }
  if(delete) check(current!=null) { "Record unavailable" } else RecordValidation.validate(type,payload!!)
  if(!delete && type=="meal_plans")require(id=="meal-plan-${payload!!.getString("date")}"){"Meal plan ID must match its date"}
  val raw=if(delete) current!!.payload else payload!!.toString()
  if(!delete && current!=null && JSONObject(current.payload).toString()==raw) return
  val expected=queued.lastOrNull()?.let{Math.addExact(it.expectedRevision,1)} ?: (current?.revision ?: 0L)
  val created=maxOf(System.currentTimeMillis(),(dao.pending().maxOfOrNull{it.createdAt} ?: 0L)+1)
  dao.put(Record(type,id,current?.revision ?: 0L,raw,delete))
  dao.enqueue(Pending(UUID.randomUUID().toString(),type,id,expected,if(delete) "{}" else raw,created,operation=if(delete) "delete" else "upsert"))
 }
 suspend fun upsert(type:String,id:String,payload:JSONObject,expectedRevision:Long?=null,expectedPayload:String?=null)=mutex.withLock {
  var newlyCompleted=false
  db.withTransaction {
   val current=dao.record(type,id)
   // A crash after commit but before draft cleanup must allow that exact saved draft to close.
   if(current!=null && !current.deleted && JSONObject(current.payload).toString()==payload.toString()) return@withTransaction
   if(expectedRevision!=null) check((current?.revision ?: 0L)==expectedRevision) { "This record changed. Reopen it before saving." }
   if(expectedPayload!=null) check(dao.record(type,id)?.payload==expectedPayload) { "This record changed locally. Reopen it before saving." }
   enqueue(type,id,payload)
   newlyCompleted=(type=="activities" && payload.optString("status")=="done" && current?.let{JSONObject(it.payload).optString("status")}!="done") ||
    (type in setOf("food_entries","weight_entries") && current==null)
  }
  if(newlyCompleted)completionEvents.tryEmit(Unit)
 }
 suspend fun delete(type:String,id:String,expectedRevision:Long?=null,expectedPayload:String?=null)=mutex.withLock {
  db.withTransaction {
   if(expectedRevision!=null) check((dao.record(type,id)?.revision ?: 0L)==expectedRevision) { "This record changed. Reopen it before deleting." }
   if(expectedPayload!=null) check(dao.record(type,id)?.payload==expectedPayload) { "This record changed locally. Reopen it before deleting." }
   enqueue(type,id,null,true)
  }
 }
 suspend fun complete(record:Record,status:String)=mutex.withLock {
  var newlyCompleted=false
  db.withTransaction {
   val current=dao.record(record.type,record.id) ?: error("Activity unavailable")
   if(JSONObject(current.payload).optString("status")==status) return@withTransaction
   val payload=JSONObject(Commands.complete(current,status))
   if(status=="done") {payload.put("completed_at",java.time.OffsetDateTime.now().toString());payload.remove("skipped_at")}
   if(status=="skipped") { payload.remove("recovery_intake");payload.remove("completed_at");payload.put("skipped_at",java.time.OffsetDateTime.now().toString()) }
   enqueue(current.type,current.id,payload)
   newlyCompleted=status=="done"
  }
  if(newlyCompleted)completionEvents.tryEmit(Unit)
 }
 suspend fun move(record:Record,scheduledAt:String)=mutex.withLock {
  db.withTransaction {
   val current=dao.record(record.type,record.id) ?: error("Activity unavailable")
   require(current.type=="activities" && !current.deleted)
   val replacement=java.time.OffsetDateTime.parse(scheduledAt)
   val original=java.time.OffsetDateTime.parse(JSONObject(current.payload).getString("scheduled_at"))
   val delta=java.time.Duration.between(original.toInstant(),replacement.toInstant())
   if(delta.isZero) return@withTransaction
   val activities=dao.recordsIncludingDeleted().filter { it.type=="activities" && !it.deleted }
   val related=mutableSetOf(current.id)
   var added=true
   while(added) {
    added=false
    for(item in activities) {
     val p=JSONObject(item.payload)
     if(item.id !in related && (p.optString("parent_id") in related || p.optString("linked_session_id") in related)) { related.add(item.id);added=true }
    }
   }
   val affected=activities.filter { it.id!=current.id && it.id in related && JSONObject(it.payload).optString("status")=="pending" }
   for(item in listOf(current)+affected) {
    val payload=JSONObject(item.payload)
    val old=payload.getString("scheduled_at")
    val next=if(item.id==current.id) scheduledAt else {
     val zone=java.time.ZoneId.of(payload.optString("time_zone","America/Chicago"))
     java.time.OffsetDateTime.parse(old).toInstant().plus(delta).atZone(zone).toOffsetDateTime().toString()
    }
    val moves=payload.optJSONArray("move_history") ?: org.json.JSONArray()
    moves.put(JSONObject().put("from",old).put("to",next).put("changed_at",java.time.Instant.now().toString()))
    payload.put("move_history",moves).put("scheduled_at",next)
    enqueue(item.type,item.id,payload)
   }
  }
 }
 suspend fun saveWeight(id:String,payload:JSONObject,expectedRevision:Long?=null,expectedPayload:String?=null)=mutex.withLock {
  var newMeasurement=false
  db.withTransaction {
   RecordValidation.validate("weight_entries",payload)
   val requested=dao.record("weight_entries",id)
   if(expectedRevision!=null) check((requested?.revision ?: 0L)==expectedRevision) { "This measurement changed. Reopen it before saving." }
   if(expectedPayload!=null) check(requested?.payload==expectedPayload) { "This measurement changed locally. Reopen it before saving." }
   val source=payload.optString("source_key").takeIf{it.isNotBlank() && it!="null"}
   val duplicate=source?.let { key -> dao.recordsIncludingDeleted().firstOrNull { it.type=="weight_entries" && JSONObject(it.payload).optString("source_key")==key } }
   check(duplicate?.deleted!=true) { "This imported measurement was removed" }
   check(duplicate==null || duplicate.id==id || requested==null) { "This tracker measurement is already saved" }
   val targetId=duplicate?.id ?: id
   val anchor=payload.optString("weekly_anchor")
   if(anchor.isNotBlank()) {
    for(other in dao.recordsIncludingDeleted().filter{it.type=="weight_entries" && !it.deleted && it.id!=targetId}) {
     val existing=JSONObject(other.payload)
     if(existing.optString("weekly_anchor")==anchor) {
      existing.remove("weekly_anchor");enqueue(other.type,other.id,existing)
     }
    }
   }
   newMeasurement=dao.record("weight_entries",targetId)==null
   enqueue("weight_entries",targetId,payload)
  }
  if(newMeasurement)completionEvents.tryEmit(Unit)
 }
 suspend fun restoreRecords(records:org.json.JSONArray):RestoreResult=mutex.withLock {
  require(records.length()<=20000) { "Archive is too large" }
  db.withTransaction {
   val parsed=(0 until records.length()).map { index ->
    val item=records.getJSONObject(index)
    val type=item.getString("entity_type");val id=item.getString("entity_id")
    require(type in setOf("activities","food_entries","weight_entries","meal_plans") && id.isNotBlank() && id.length<=200)
    require(item.getLong("revision")>=0)
    val payload=when(val raw=item.get("payload")) { is String -> JSONObject(raw);is JSONObject -> raw;else -> error("Invalid record payload") }
    RecordValidation.validate(type,payload)
    if(type=="meal_plans")require(id=="meal-plan-${payload.getString("date")}"){"Meal plan ID must match its date"}
    Record(type,id,0,payload.toString(),item.getBoolean("deleted"))
   }
   require(parsed.map{it.type to it.id}.distinct().size==parsed.size) { "Duplicate archive record IDs" }
   val existing=dao.recordsIncludingDeleted()
   val activityIds=(parsed.filter{!it.deleted}+existing).filter{it.type=="activities"}.map{it.id}.toSet()
   parsed.forEach { record ->
    val payload=JSONObject(record.payload)
    for(key in listOf("parent_id","linked_session_id","activity_id","planned_activity_id")) {
     if(payload.has(key) && !payload.isNull(key)) require(payload.getString(key) in activityIds) { "Missing linked activity" }
    }
   }
   val additions=parsed.filter{record -> !record.deleted && existing.none{it.type==record.type && it.id==record.id}}
   val weights=(existing.filter{!it.deleted}+additions).filter{it.type=="weight_entries"}
   val anchors=weights.mapNotNull { JSONObject(it.payload).optString("weekly_anchor").takeIf{a->a.isNotBlank() && a!="null"} }
   require(anchors.distinct().size==anchors.size) { "Archive conflicts with the selected weekly weight" }
   val sources=(existing+additions).filter{it.type=="weight_entries"}.mapNotNull { JSONObject(it.payload).optString("source_key").takeIf{k->k.isNotBlank() && k!="null"} }
   require(sources.distinct().size==sources.size) { "Archive duplicates an imported measurement" }
   val parents=(existing.filter{it.type=="activities"}+additions.filter{it.type=="activities"}).associate { it.id to JSONObject(it.payload).optString("parent_id") }
   for(record in additions.filter{it.type=="activities"}) {
    val visited=mutableSetOf<String>();var next=record.id
    while(next.isNotBlank()) { require(visited.add(next)) { "Archive contains a cyclic activity link" };next=parents[next] ?: "" }
   }
   additions.forEach { enqueue(it.type,it.id,JSONObject(it.payload)) }
   RestoreResult(additions.size,parsed.size-additions.size)
  }
 }
 suspend fun saveDraft(key:String,text:String) { require(key.isNotBlank());dao.meta(Metadata("draft:$key",text)) }
 suspend fun loadDraft(key:String):String?=dao.meta("draft:$key")
 suspend fun clearDraft(key:String) { dao.removeMeta("draft:$key") }
 // Pending writes and their original revision are durable. Never overwrite one during a pull.
 suspend fun sync(api:SyncApi)=mutex.withLock {
  val bound=dao.meta("server_identity")
  check(bound==null || bound==api.identity) { "This database belongs to a different server" }
  if(bound==null) {
   // Verify authorization before binding this cache; never send writes to an unbound host.
   api.request("/v1/changes?after=0&limit=1")
   dao.meta(Metadata("server_identity",api.identity))
  }
  for(pending in dao.pending()) {
   if(pending.error!=null || dao.pendingFor(pending.type,pending.id)?.commandId!=pending.commandId || dao.pendingForEntity(pending.type,pending.id).any{it.error!=null}) continue
   try {
    val response=api.request("/v1/commands",Commands.envelope(pending))
    db.withTransaction {
     val accepted=Commands.record(response.getJSONObject("record"))
     check(accepted.type==pending.type && accepted.id==pending.id && accepted.revision==pending.expectedRevision+1 && accepted.deleted==(pending.operation=="delete")) { "Invalid server acknowledgement" }
     val local=dao.record(accepted.type,accepted.id)
     dao.acknowledge(pending.commandId)
     val next=dao.pendingFor(accepted.type,accepted.id)
     if(local==null || accepted.revision>=local.revision) {
      dao.put(if(next!=null && local!=null) local.copy(revision=accepted.revision) else accepted)
     }
    }
   } catch(conflict:RevisionConflict) {
    db.withTransaction {
     dao.error(pending.commandId,"Changed on server. Review before replacing your local change.")
     conflict.remote?.let { dao.meta(Metadata("conflict:${pending.commandId}",it.toString())) }
    }
   }
  }
  var more=true
  while(more) {
   val cursor=dao.meta("cursor") ?: "0"
   val response=api.request("/v1/changes?after=$cursor&limit=100")
   val nextCursor=response.getLong("next_cursor")
   check(nextCursor>=cursor.toLong() && (!response.getBoolean("has_more") || nextCursor>cursor.toLong())) { "Invalid server change cursor" }
   db.withTransaction {
    val changes=response.getJSONArray("changes")
    for(i in 0 until changes.length()) {
     val incoming=Commands.record(changes.getJSONObject(i))
     val pending=dao.pendingFor(incoming.type,incoming.id)
     if(pending==null) {
      val current=dao.record(incoming.type,incoming.id)
      if(current==null || incoming.revision>=current.revision) dao.put(incoming)
     }
     else if(incoming.revision>pending.expectedRevision) {
      dao.error(pending.commandId,"Changed on server. Review before replacing your local change.")
      val previous=dao.meta("conflict:${pending.commandId}")?.let{JSONObject(it).optLong("revision")} ?: -1L
      if(incoming.revision>=previous) dao.meta(Metadata("conflict:${pending.commandId}",changes.getJSONObject(i).toString()))
     }
    }
    dao.meta(Metadata("cursor",response.getLong("next_cursor").toString()))
   }
   more=response.getBoolean("has_more")
  }
  interpreter=BackendLlmClient(api)
 }
 suspend fun acceptServer(pending:Pending)=mutex.withLock {
  db.withTransaction {
   val current=dao.pendingFor(pending.type,pending.id)
   check(current?.commandId==pending.commandId) { "This conflict has already been resolved" }
   val remote=dao.meta("conflict:${pending.commandId}") ?: error("Sync again to retrieve the server version")
   val accepted=Commands.record(JSONObject(remote))
   val local=dao.record(pending.type,pending.id)
   check(local==null || accepted.revision>=local.revision) { "Sync again to review the latest record" }
   dao.put(accepted)
   // Discard the whole local chain, not just its failed creation, to avoid orphan edits.
   for(queued in dao.pendingForEntity(pending.type,pending.id)) {
    dao.acknowledge(queued.commandId);dao.removeMeta("conflict:${queued.commandId}")
   }
  }
 }
}
