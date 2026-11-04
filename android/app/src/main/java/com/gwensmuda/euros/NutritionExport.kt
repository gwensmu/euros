package com.gwensmuda.euros

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.Metadata as HealthMetadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit

object NutritionExport {
 const val ENABLED="nutrition_export_enabled"
 const val STATUS="nutrition_export_status"
 val permission=HealthPermission.getWritePermission(NutritionRecord::class)
 private val mutex=Mutex()
 fun clientId(id:String)="euros-food-$id"
 fun record(food:Record,version:Long):NutritionRecord {
  require(food.type=="food_entries" && !food.deleted)
  val payload=JSONObject(food.payload)
  val at=OffsetDateTime.parse(payload.getString("consumed_at"))
  val instant=at.toInstant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
  val nutrients=payload.optJSONObject("nutrients") ?: JSONObject()
  fun value(key:String):Double?=if(nutrients.isNull(key))null else nutrients.getDouble(key)
  return NutritionRecord(
   startTime=instant,endTime=instant.plusMillis(1),
   startZoneOffset=at.offset,endZoneOffset=at.offset,
   name=payload.optString("raw_text").takeIf{it.isNotBlank()},
   energy=value("kcal")?.let{Energy.kilocalories(it)},
   protein=value("protein_g")?.let{Mass.grams(it)},
   totalCarbohydrate=value("carbs_g")?.let{Mass.grams(it)},
   totalFat=value("fat_g")?.let{Mass.grams(it)},
   metadata=HealthMetadata.manualEntry(clientRecordId=clientId(food.id),clientRecordVersion=version),
  )
 }
 fun fingerprint(food:Record):String {
  val bytes=(food.deleted.toString()+":"+food.payload).toByteArray()
  return java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
 }
 fun envelope(food:Record,previous:JSONObject?,now:Long=System.currentTimeMillis()):JSONObject {
  val hash=fingerprint(food)
  if(previous?.optString("fingerprint")==hash)return previous
  return JSONObject().put("fingerprint",hash).put("version",maxOf(now,(previous?.optLong("version") ?: 0)+1)).put("acknowledged",false)
 }
 fun request(context:Context) {
  WorkManager.getInstance(context).enqueueUniqueWork("euros-nutrition-export",ExistingWorkPolicy.APPEND_OR_REPLACE,OneTimeWorkRequestBuilder<NutritionExportWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build())
 }
 fun schedule(context:Context) {
  WorkManager.getInstance(context).enqueueUniquePeriodicWork("euros-nutrition-export-periodic",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<NutritionExportWorker>(15,TimeUnit.MINUTES).build())
 }
 suspend fun sync(context:Context,dao:EurosDao):Boolean=mutex.withLock {
  if(dao.meta(ENABLED)!="true")return@withLock true
  if(HealthConnectClient.getSdkStatus(context)!=HealthConnectClient.SDK_AVAILABLE){
   dao.meta(Metadata(STATUS,"Health Connect unavailable"));return@withLock true
  }
  try {
   val client=HealthConnectClient.getOrCreate(context)
   if(permission !in client.permissionController.getGrantedPermissions()){
    dao.meta(Metadata(STATUS,"Nutrition write access needed"));return@withLock true
   }
   val foods=dao.recordsIncludingDeleted().filter{it.type=="food_entries"}
   for(food in foods) {
    if(dao.meta(ENABLED)!="true")return@withLock true
    val key="nutrition_export:${food.id}"
    val previous=dao.meta(key)?.let(::JSONObject)
    val next=envelope(food,previous)
    if(next.optBoolean("acknowledged"))continue
    // Persist the version before the external call. A crash retries the same identity/version.
    dao.meta(Metadata(key,next.toString()))
    if(food.deleted)client.deleteRecords(NutritionRecord::class,recordIdsList=emptyList(),clientRecordIdsList=listOf(clientId(food.id)))
    else client.insertRecords(listOf(record(food,next.getLong("version"))))
    dao.meta(Metadata(key,next.put("acknowledged",true).toString()))
   }
   dao.meta(Metadata(STATUS,"Food logs synced"))
   true
  }catch(e:CancellationException){throw e}
   catch(_:SecurityException){dao.meta(Metadata(STATUS,"Nutrition write access needed"));true}
   catch(_:Exception){dao.meta(Metadata(STATUS,"Couldn't sync food logs. Tap Sync food logs to retry."));false}
 }
}
class NutritionExportWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
 override suspend fun doWork():Result {
  val dao=(applicationContext as EurosApplication).repository.dao
  return if(NutritionExport.sync(applicationContext,dao))Result.success() else Result.retry()
 }
}
