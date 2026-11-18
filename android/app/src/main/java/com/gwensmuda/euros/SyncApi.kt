package com.gwensmuda.euros

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

// Transport only: cache binding, retries and conflict resolution belong to Repository.
interface SyncApi { val identity:String get() = "test"; val accountId:String? get() = null; fun request(path:String,body:JSONObject?=null):JSONObject }

/**
 * The access token each request carries.
 *
 * A signed-in account holds a short-lived token, so the transport asks for a fresh one when the
 * server rejects the current one. A server token never changes and simply refuses to refresh.
 */
interface TokenSource { fun current():String; fun refreshed(expired:String):String? }
class StaticToken(private val token:String):TokenSource {
 override fun current()=token
 override fun refreshed(expired:String):String?=null
}

class Api(private val base:String,private val tokens:TokenSource):SyncApi {
 constructor(base:String,token:String):this(base,StaticToken(token))
 override val identity:String get() = canonicalServerIdentity(base)
 override val accountId:String by lazy {
  request("/v1/account").getString("user_id").also {
   require(Regex("usr-[0-9a-f]{32}").matches(it)) { "Invalid account identity" }
  }
 }
 override fun request(path:String,body:JSONObject?):JSONObject {
  val uri=URI(base)
  require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null && (uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http"))) { "Use a valid HTTPS server URL" }
  val token=tokens.current()
  return try { attempt(path,body,token) }
  catch(failure:ApiFailure) {
   // One refresh, one retry: an expired access token is routine, a revoked grant is not.
   val renewed=if(failure.status==401) tokens.refreshed(token) else null
   if(renewed==null) throw failure else attempt(path,body,renewed)
  }
 }
 private fun attempt(path:String,body:JSONObject?,token:String):JSONObject {
  val connection=URL(base.trimEnd('/')+path).openConnection() as HttpURLConnection
  try {
   connection.instanceFollowRedirects=false
   connection.connectTimeout=15000; connection.readTimeout=if(path.startsWith("/v1/interpret/")) 75000 else 20000
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
  code==401 || code==403 -> "Connection authorization failed. Sign in again in Settings"
  error=="model_not_configured" -> "The model isn't configured on your server yet"
  error=="model_daily_limit" -> "Daily model limit reached. Try again tomorrow"
  error=="model_hourly_limit" -> "Hourly food limit reached. Try again later"
  error=="model_budget_exhausted" -> "This server's monthly model budget is used up"
  error=="model_account_budget_exhausted" -> "Your monthly model allowance is used up"
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
 return if(normalized=="https://euros.example.com") "https://euros.example.com" else normalized
}
