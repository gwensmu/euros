package com.gwensmuda.euros

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class BackgroundSync(private val context:Context):SyncScheduling {
 private val work get()=WorkManager.getInstance(context)
 private val network=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
 override fun enable() {
  work.enqueueUniquePeriodicWork("euros-periodic-sync",ExistingPeriodicWorkPolicy.KEEP,
   PeriodicWorkRequestBuilder<SyncWorker>(15,TimeUnit.MINUTES).setConstraints(network)
    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag(TAG).build())
 }
 override fun request() {
  // Append rather than KEEP: a write arriving as the running worker finishes must get another pass.
  work.enqueueUniqueWork("euros-requested-sync",ExistingWorkPolicy.APPEND_OR_REPLACE,
   OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(network)
    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag(TAG).build())
 }
 override fun cancel(){work.cancelAllWorkByTag(TAG)}
 companion object { private const val TAG="euros-sync" }
}
class SyncWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
 override suspend fun doWork():Result = when((applicationContext as EurosApplication).connection.sync()) {
  SyncOutcome.SUCCESS -> Result.success()
  SyncOutcome.RETRY -> Result.retry()
  SyncOutcome.RECONNECT -> Result.failure()
 }
}

/** Acknowledgements/removals never trigger requests; only new or newly retryable commands do. */
internal class PendingSyncTrigger {
 private var previous=emptySet<String>()
 fun changed(pending:List<Pending>):Boolean {
  val current=pending.filter{it.error==null}.map{it.commandId}.toSet()
  val added=(current-previous).isNotEmpty();previous=current;return added
 }
}
