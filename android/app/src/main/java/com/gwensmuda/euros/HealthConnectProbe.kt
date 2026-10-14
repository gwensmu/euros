package com.gwensmuda.euros

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.Record as HealthRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

/** Diagnostic only: no raw records leave this object, are persisted, or sent to the backend. */
data class HealthSourceCount(val sourcePackage:String, val recordCount:Int, val sampleCount:Int=0)
data class HealthCategoryReport(val name:String, val granted:Boolean, val sources:List<HealthSourceCount> = emptyList(), val truncated:Boolean=false, val error:String?=null)
data class HealthCoverageReport(val sourcePackage:String, val sessionCount:Int, val sessionsWithSamples:Int, val coveredSeconds:Long, val sessionSeconds:Long, val heartRateReadable:Boolean, val partial:Boolean)
data class HealthProbeReport(val available:Boolean, val status:String, val days:Int, val categories:List<HealthCategoryReport> = emptyList(), val coverage:List<HealthCoverageReport> = emptyList())

class HealthConnectProbe(private val context:Context) {
 companion object {
  val types:List<Pair<String,KClass<out HealthRecord>>> = listOf(
   "Exercise" to ExerciseSessionRecord::class, "Heart rate" to HeartRateRecord::class,
   "Resting heart rate" to RestingHeartRateRecord::class, "Weight" to WeightRecord::class,
   "Steps" to StepsRecord::class, "Distance" to DistanceRecord::class,
   "Active energy" to ActiveCaloriesBurnedRecord::class, "Total energy" to TotalCaloriesBurnedRecord::class)
  val permissions:Set<String> = types.map{HealthPermission.getReadPermission(it.second)}.toSet()
  fun availability(context:Context):Int = HealthConnectClient.getSdkStatus(context)
 }
 suspend fun inspect(days:Int=7, now:Instant=Instant.now()):HealthProbeReport {
  require(days in 1..30)
  val status=availability(context)
  if(status!=HealthConnectClient.SDK_AVAILABLE) return HealthProbeReport(false,if(status==HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) "Health Connect needs an update" else "Health Connect unavailable",days)
  val client=HealthConnectClient.getOrCreate(context)
  val granted=client.permissionController.getGrantedPermissions()
  val start=now.minus(Duration.ofDays(days.toLong()))
  val categories=mutableListOf<HealthCategoryReport>()
  val sessions=mutableListOf<ExerciseSessionRecord>()
  val sampleTimes=mutableMapOf<String,MutableList<Instant>>()
  for((name,type) in types) {
   if(HealthPermission.getReadPermission(type) !in granted) { categories.add(HealthCategoryReport(name,false));continue }
   try {
    val (records,truncated)=readPages(client,type,start,now)
    val sources=records.groupBy{it.metadata.dataOrigin.packageName}.map { (source,items) ->
     HealthSourceCount(source,items.size,items.filterIsInstance<HeartRateRecord>().sumOf{r->r.samples.count{it.time>=start && it.time<now}})
    }.sortedBy{it.sourcePackage}
    categories.add(HealthCategoryReport(name,true,sources,truncated))
    sessions.addAll(records.filterIsInstance<ExerciseSessionRecord>())
    records.filterIsInstance<HeartRateRecord>().forEach{r->sampleTimes.getOrPut(r.metadata.dataOrigin.packageName){mutableListOf()}.addAll(r.samples.map{it.time}.filter{it>=start && it<now})}
   } catch(cancel:CancellationException) { throw cancel }
   catch(_:SecurityException) { categories.add(HealthCategoryReport(name,false,error="Permission changed; check access")) }
   catch(_:Exception) { categories.add(HealthCategoryReport(name,true,error="Couldn't read this category; retry")) }
  }
  // Keep provider streams independent. Overlapping sessions are not merged or credited as completion.
  val coverage=sessions.groupBy{it.metadata.dataOrigin.packageName}.map { (source,items) ->
   val times=sampleTimes[source].orEmpty().distinct().sorted()
   var withSamples=0
   var covered=0L
   var duration=0L
   for(session in items) {
    val from=maxOf(start,session.startTime);val to=minOf(now,session.endTime)
    if(to<=from) continue
    val inside=times.filter{it>=from && it<=to}
    if(inside.isNotEmpty()) withSamples++
    covered+=HealthCoverage.coveredSeconds(from,to,inside)
    duration+=Duration.between(from,to).seconds
   }
   HealthCoverageReport(source,items.size,withSamples,covered,duration,
    categories.any{it.name=="Heart rate" && it.granted && it.error==null},
    categories.any{it.name in setOf("Exercise","Heart rate") && it.truncated})
  }.sortedBy{it.sourcePackage}
  return HealthProbeReport(true,"Available",days,categories,coverage)
 }
 private suspend fun <T:HealthRecord> readPages(client:HealthConnectClient,type:KClass<T>,start:Instant,end:Instant):Pair<List<T>,Boolean> {
  val records=mutableListOf<T>();var token:String?=null;var pages=0
  do {
   val page=client.readRecords(ReadRecordsRequest(recordType=type,timeRangeFilter=TimeRangeFilter.between(start,end),pageSize=1000,pageToken=token))
   records.addAll(page.records);token=page.pageToken;pages++
  } while(token!=null && pages<20)
  // Bounded foreground diagnostic: clearly report partial data instead of implying full coverage.
  return records.distinctBy{it.metadata.dataOrigin.packageName to it.metadata.id} to (token!=null)
 }
}

object HealthCoverage {
 /** Sample coverage, not physiological analysis: no extrapolation and no bridging gaps >60 seconds. */
 fun coveredSeconds(start:Instant,end:Instant,sampleTimes:List<Instant>):Long {
  if(end<=start) return 0
  val times=sampleTimes.filter{it>=start && it<=end}.distinct().sorted()
  return times.zipWithNext().sumOf{(a,b)->Duration.between(a,b).seconds.takeIf{it in 1..60} ?: 0L}
 }
}
