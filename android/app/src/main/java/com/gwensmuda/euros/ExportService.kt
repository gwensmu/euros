package com.gwensmuda.euros

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant

/** Explicit document-provider export. No credentials, access tokens or HC grants. */
class ExportService(private val context:Context,private val dao:EurosDao) {
 companion object {
  fun units()=JSONObject().put("body_weight","lb").put("food","g").put("macros","g").put("energy","kcal")
  fun validateEnvelope(archive:JSONObject):JSONArray {
   require(archive.get("schema_version") in setOf(1,2)){"Unsupported export version"}
   val actual=archive.getJSONObject("units");val expected=units()
   require(expected.keys().asSequence().all{actual.optString(it)==expected.getString(it)}){"Unsupported export units"}
   if(archive.getInt("schema_version")==2) { BackupState.parse(archive);BackupState.health(archive) }
   return archive.getJSONArray("records")
  }
 }
 suspend fun read(uri:Uri):JSONObject=withContext(Dispatchers.IO) {
  val input=context.contentResolver.openInputStream(uri) ?: error("Couldn't open export document")
  val data=input.use{stream->val bytes=ByteArrayOutputStream();val buffer=ByteArray(8192);var count=stream.read(buffer)
   while(count!=-1){check(bytes.size()+count<=20_000_000){"Export exceeds 20 MB"};bytes.write(buffer,0,count);count=stream.read(buffer)};bytes.toString("UTF-8")}
  JSONObject(data).also{validateEnvelope(it)}
 }
 suspend fun document(includeHealth:Boolean=false):JSONObject {
  val snapshot=dao.exportSnapshot();val records=snapshot.records;val pending=snapshot.pending
  val archive=JSONObject().put("schema_version",2).put("exported_at",Instant.now().toString())
   .put("units",units()).put("records",JSONArray().apply{records.forEach{put(JSONObject().put("entity_type",it.type).put("entity_id",it.id).put("revision",it.revision).put("deleted",it.deleted).put("payload",JSONObject(it.payload)))}})
   .put("pending_commands",JSONArray().apply{pending.forEach{put(Commands.envelope(it))}})
   .put("health_included",includeHealth)
   .put("local_state",BackupState.export(snapshot.metadata))
  if(includeHealth) snapshot.metadata.firstOrNull{it.key==HealthImport.KEY}?.value?.let{archive.put("health_imports",HealthSnapshotStorage.decode(it))}
  return archive
 }
 suspend fun write(uri:Uri,includeHealth:Boolean=false)=withContext(Dispatchers.IO) {
  val data=document(includeHealth).toString(2)
  require(data.toByteArray(Charsets.UTF_8).size<=20_000_000){"Backup exceeds 20 MB; export without tracker data"}
  val stream=context.contentResolver.openOutputStream(uri,"wt") ?: error("Couldn't open export document")
  stream.bufferedWriter().use{it.write(data)}
 }
}
