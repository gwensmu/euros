package com.gwensmuda.euros

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

// Transport only: cache binding, retries and conflict resolution belong to Repository.
interface SyncApi { val identity:String get() = "test"; fun request(path:String,body:JSONObject?=null):JSONObject }
class Api(private val base:String,private val token:String):SyncApi {
 override val identity:String get() = URI(base.trimEnd('/')).normalize().toString()
 override fun request(path:String,body:JSONObject?):JSONObject {
  val uri=URI(base)
  require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null && (uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http"))) { "Use a valid HTTPS server URL" }
  val connection=URL(base.trimEnd('/')+path).openConnection() as HttpURLConnection
  try {
   connection.instanceFollowRedirects=false
   connection.connectTimeout=15000; connection.readTimeout=if(path.startsWith("/v1/review/")) 150000 else if(path.startsWith("/v1/interpret/")) 75000 else 20000
   connection.setRequestProperty("Authorization","Bearer $token")
   if(body!=null) { connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.outputStream.use { it.write(body.toString().toByteArray()) } }
   val code=connection.responseCode
   val text=(if(code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
   if(code==409) throw RevisionConflict(JSONObject(text).optJSONObject("current"))
   check(code in 200..299) { backendErrorMessage(code,text) }
   return JSONObject(text)
  } finally { connection.disconnect() }
 }
}
class RevisionConflict(val remote:JSONObject?):Exception("This record changed on another device")

/** Never display arbitrary server/provider error bodies or credentials in the UI. */
internal fun backendErrorMessage(code:Int,body:String):String {
 val error=runCatching{JSONObject(body).optString("error")}.getOrDefault("")
 return when {
  code==401 || code==403 -> "Connection authorization failed. Reconnect in Settings"
  error=="model_not_configured" -> "The model isn't configured on your server yet"
  code==429 || error=="model_busy" -> "Too many requests. Try again shortly"
  error=="model_refused" -> "Couldn't interpret this request. Try different wording"
  code==502 || code==504 -> "The model couldn't finish. Try again"
  code==503 -> "The server is temporarily unavailable. Try again"
  else -> "Server returned $code"
 }
}
