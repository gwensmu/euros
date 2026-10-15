package com.gwensmuda.euros

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** Prefetch never blocks scheduling. Delivery only reads a context-matched local result. */
class ReminderCopyCache(private val context:Context,private val dao:EurosDao) {
 companion object {private val mutex=Mutex();private const val PREFIX="reminder_copy:";private const val RETRY="reminder_copy_retry:"}
 fun prefetch(plans:List<RichReminder>) {
  val app=context.applicationContext as? EurosApplication ?: return
  if(app.repository.reminderApi==null)return
  app.applicationScope.launch(Dispatchers.IO) {mutex.withLock {
   // A bounded horizon avoids spending on distant plans that may change.
   val now=Instant.now()
   for(plan in plans.filter{it.kind!="water" && it.due>now && it.due<=now.plusSeconds(86400)}.take(6)) {
    if(dao.meta(PREFIX+plan.contextKey)!=null)continue
    val retry=dao.meta(RETRY+plan.contextKey)?.toLongOrNull() ?: 0
    if(retry>System.currentTimeMillis())continue
    val api=app.repository.reminderApi ?: break
    try {
     dao.meta(Metadata(RETRY+plan.contextKey,(System.currentTimeMillis()+3_600_000).toString()))
     val recent=dao.allMetadata().filter{it.key.startsWith(PREFIX)}.mapNotNull{runCatching{JSONObject(it.value)}.getOrNull()}.sortedByDescending{it.optLong("cached_at")}.take(8).map{it.optString("joke_id")}.filter{it.isNotBlank()}.distinct()
     val response=api.request("/v1/reminders/copy",JSONObject().put("kind",plan.kind).put("context_key",plan.contextKey).put("allow_skip_joke",plan.allowSkipJoke).put("facts",plan.facts).put("recent_joke_ids",JSONArray(recent)))
     require(response.getString("context_key")==plan.contextKey && response.getString("prompt_version")=="weasel-reminders-v1")
     require(response.getString("joke").length<=250)
     if(app.repository.reminderApi!==api)break
     dao.meta(Metadata(PREFIX+plan.contextKey,response.put("cached_at",System.currentTimeMillis()).toString()))
     dao.removeMeta(RETRY+plan.contextKey)
    }catch(e:CancellationException){throw e}catch(_:Exception){ /* Facts remain available offline, without model text. */ }
   }
   // Regenerable cache only; never export it or touch domain records.
   val cutoff=System.currentTimeMillis()-7*86_400_000L
   dao.allMetadata().filter{it.key.startsWith(PREFIX) || it.key.startsWith(RETRY)}.forEach { item ->
    val timestamp=if(item.key.startsWith(PREFIX))runCatching{JSONObject(item.value).optLong("cached_at")}.getOrDefault(0) else item.value.toLongOrNull() ?: 0
    if(timestamp<cutoff)dao.removeMeta(item.key)
   }
  }}
 }
 suspend fun joke(plan:RichReminder):String? = dao.meta(PREFIX+plan.contextKey)?.let { raw ->runCatching {
  val result=JSONObject(raw)
  if(result.optString("context_key")!=plan.contextKey || result.optString("prompt_version")!="weasel-reminders-v1")null
  else result.optString("joke").takeIf{it.isNotBlank() && it.length<=250}
 }.getOrNull() }
}
