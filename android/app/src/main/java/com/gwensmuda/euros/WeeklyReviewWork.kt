package com.gwensmuda.euros

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/** Durable phone request identity; WorkManager resumes polling without repeating model work. */
object WeeklyReviewWork {
 private val mutex=Mutex()
 fun key(week:LocalDate)="weekly_review_job:$week"
 private fun schedule(context:Context,week:LocalDate,policy:ExistingWorkPolicy=ExistingWorkPolicy.KEEP) {
  val work=OneTimeWorkRequestBuilder<WeeklyReviewWorker>().setInputData(workDataOf("week" to week.toString()))
   .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
  WorkManager.getInstance(context).enqueueUniqueWork(key(week),policy,work)
 }
 suspend fun enqueue(context:Context,repo:Repository,week:LocalDate,records:List<Record>) {
  val created=prepare(repo,week,records)
  try{schedule(context,week,if(created)ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP)}
  catch(e:Exception){fail(repo,week,"Couldn’t start the wrap-up. Try again.");throw e}
 }
 suspend fun prepare(repo:Repository,week:LocalDate,records:List<Record>)=mutex.withLock {
  val old=repo.dao.meta(key(week))?.let{JSONObject(it)}
  if(old?.optString("status") in setOf("queued","running"))return@withLock false
  val snapshot=JSONArray()
  BackendLlmClient.weeklySnapshot(week,records).forEach{snapshot.put(JSONObject().put("type",it.type).put("id",it.id).put("revision",it.revision).put("payload",it.payload).put("deleted",it.deleted))}
  repo.dao.meta(Metadata(key(week),JSONObject().put("request_id",UUID.randomUUID().toString())
   .put("status","queued").put("created_at",System.currentTimeMillis()).put("records",snapshot)
   .put("fingerprint",WeeklyReviewCache.fingerprint(week,records,LocalDate.now(PlanDomain.zone))).toString()))
  true
 }
 suspend fun resume(context:Context,repo:Repository) {
  repo.dao.allMetadata().filter{it.key.startsWith("weekly_review_job:")}.forEach {
   if(JSONObject(it.value).optString("status") in setOf("queued","running"))schedule(context,LocalDate.parse(it.key.substringAfter(':')))
  }
 }
 suspend fun execute(repo:Repository,week:LocalDate) {
  val state=JSONObject(repo.dao.meta(key(week)) ?: return)
  if(state.optString("status") !in setOf("queued","running"))return
  suspend fun save(status:String,error:String?=null) {
   state.put("status",status).put("error",error ?: JSONObject.NULL)
   repo.dao.meta(Metadata(key(week),state.toString()))
  }
  save("running")
  try {
   val array=state.getJSONArray("records")
   val records=(0 until array.length()).map{val r=array.getJSONObject(it);Record(r.getString("type"),r.getString("id"),r.getLong("revision"),r.getString("payload"),r.getBoolean("deleted"))}
   val result=WeeklyReviewCache.validate(repo.reviewWeekJob(state.getString("request_id"),week,records),week)
   repo.dao.meta(Metadata(WeeklyReviewCache.key(week),WeeklyReviewCache.encode(state.getString("fingerprint"),result)))
   save("completed")
  } catch(e:CancellationException){throw e}
  catch(e:java.io.IOException){save("queued");throw e}
  catch(e:Exception){save("failed",WeeklyReviewCache.errorMessage(e));throw e}
 }
 suspend fun fail(repo:Repository,week:LocalDate,message:String) {
  val state=JSONObject(repo.dao.meta(key(week)) ?: return)
  state.put("status","failed").put("error",message)
  repo.dao.meta(Metadata(key(week),state.toString()))
 }
 suspend fun waiting(repo:Repository,week:LocalDate) {
  val state=JSONObject(repo.dao.meta(key(week)) ?: return)
  state.put("status","queued").put("error",JSONObject.NULL)
  repo.dao.meta(Metadata(key(week),state.toString()))
 }
}
class WeeklyReviewWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
 override suspend fun doWork():Result {
  val week=LocalDate.parse(inputData.getString("week") ?: return Result.failure())
  val app=applicationContext as EurosApplication
  return try {app.connection.initialize();WeeklyReviewWork.execute(app.repository,week);Result.success()}
  catch(e:CancellationException){throw e}
  catch(e:java.io.IOException) {
   if(runAttemptCount<2){WeeklyReviewWork.waiting(app.repository,week);Result.retry()}else {WeeklyReviewWork.fail(app.repository,week,"Couldn’t reconnect to the wrap-up. Try again.");Result.failure()}
  } catch(e:Exception){WeeklyReviewWork.fail(app.repository,week,WeeklyReviewCache.errorMessage(e));Result.failure()}
 }
}
