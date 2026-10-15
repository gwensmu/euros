package com.gwensmuda.euros

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

// Transport only: cache binding, retries and conflict resolution belong to Repository.
interface SyncApi { val identity:String get() = "test"; fun request(path:String,body:JSONObject?=null):JSONObject }
class Api(private val base:String,private val token:String):SyncApi {
 override val identity:String get() = canonicalServerIdentity(base)
 override fun request(path:String,body:JSONObject?):JSONObject {
  val uri=URI(base)
  require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null && (uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http"))) { "Use a valid HTTPS server URL" }
  val connection=URL(base.trimEnd('/')+path).openConnection() as HttpURLConnection
  try {
   connection.instanceFollowRedirects=false
   connection.connectTimeout=15000; connection.readTimeout=if(path.startsWith("/v1/review/")) 150000 else if(path.startsWith("/v1/interpret/")) 75000 else 20000
   connection.setRequestProperty("User-Agent","Euros-Android/${BuildConfig.VERSION_NAME}")
   connection.setRequestProperty("Authorization","Bearer $token")
   if(body!=null) { connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.outputStream.use { it.write(body.toString().toByteArray()) } }
   val code=connection.responseCode
   val text=(if(code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
   if(code==409) throw RevisionConflict(JSONObject(text).optJSONObject("current"))
   if(code !in 200..299) throw ApiFailure(code,backendErrorMessage(code,text))
   return JSONObject(text)
  } finally { connection.disconnect() }
 }
}
class ApiFailure(val status:Int,message:String):Exception(message)
class RevisionConflict(val remote:JSONObject?):Exception("This record changed on another device")

/** Never display arbitrary server/provider error bodies or credentials in the UI. */
internal fun backendErrorMessage(code:Int,body:String):String {
 val error=runCatching{JSONObject(body).optString("error")}.getOrDefault("")
 return when {
  code==401 || code==403 -> "Connection authorization failed. Reconnect in Settings"
  error=="model_not_configured" -> "The model isn't configured on your server yet"
  error=="model_daily_limit" -> "Daily model limit reached. Try again tomorrow"
  code==429 || error=="model_busy" -> "Too many requests. Try again shortly"
  error=="model_refused" -> "Couldn't interpret this request. Try different wording"
  code==502 || code==504 -> "The model couldn't finish. Try again"
  code==503 -> "The server is temporarily unavailable. Try again"
  else -> "Server returned $code"
 }
}

// These two exact HTTPS origins route to the same Euros database. Preserve the established
// cache binding when moving from home-network TLS to the public authenticated endpoint.
internal fun canonicalServerIdentity(base:String):String {
 val normalized=URI(base.trimEnd('/')).normalize().toString()
 return if(normalized=="https://euros.example.com") "https://euros-lan.example.com" else normalized
}
