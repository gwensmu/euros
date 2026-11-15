package com.gwensmuda.euros

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Upgrade compatibility only: retire old queued wrap-up work without network or model calls. */
class WeeklyReviewWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
 override suspend fun doWork():Result = Result.success()
}
