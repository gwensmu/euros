package com.gwensmuda.euros

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.work.*
import java.util.concurrent.TimeUnit
import java.time.Instant
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

object HealthBackground {
 fun available(context:Context):Boolean=runCatching {
  HealthConnectClient.getSdkStatus(context)==HealthConnectClient.SDK_AVAILABLE && HealthConnectClient.getOrCreate(context).features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)==HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
 }.getOrDefault(false)
 suspend fun allowed(context:Context)=available(context) && HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
 fun schedule(context:Context) {
  WorkManager.getInstance(context).enqueueUniquePeriodicWork("euros-health-refresh",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<HealthRefreshWorker>(15,TimeUnit.MINUTES).build())
 }
 suspend fun refresh(context:Context,background:Boolean=false) {
  val app=context.applicationContext as EurosApplication
  if(background && !allowed(context))return
  try {
   HealthImport(context,app.repository.dao).refresh()
   app.repository.dao.meta(Metadata("health_refresh_error",""))
   ReminderScheduler(context,app.repository.dao).reconcile(app.repository.dao.recordsIncludingDeleted().filter{!it.deleted})
  } catch(e:CancellationException){throw e} catch(_:Exception){
   app.repository.dao.meta(Metadata("health_refresh_error","Couldn't refresh Health Connect. Check tracker access."))
  }
 }
}
class HealthRefreshWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
 override suspend fun doWork():Result {
  HealthBackground.refresh(applicationContext,true)
  return Result.success()
 }
}
