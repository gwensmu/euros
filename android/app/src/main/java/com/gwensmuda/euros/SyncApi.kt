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
   connection.connectTimeout=15000; connection.readTimeout=20000
   connection.setRequestProperty("Authorization","Bearer $token")
   if(body!=null) { connection.requestMethod="POST";connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json");connection.outputStream.use { it.write(body.toString().toByteArray()) } }
   val code=connection.responseCode
   val text=(if(code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
   if(code==409) throw RevisionConflict(JSONObject(text).optJSONObject("current"))
   check(code in 200..299) { when(code) {401,403 -> "Connection authorization failed";else -> "Server returned $code"} }
   return JSONObject(text)
  } finally { connection.disconnect() }
 }
}
class RevisionConflict(val remote:JSONObject?):Exception("This record changed on another device")
