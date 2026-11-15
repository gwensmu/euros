package com.gwensmuda.euros

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

// This app's own redirect scheme. The server only redirects to addresses a client registered.
const val OAUTH_REDIRECT="com.gwensmuda.euros:/oauth2redirect"
const val OAUTH_SCOPE="training.read training.write"
private const val CLIENT_NAME="Euros on Android"

/** Where one server's OAuth endpoints live, as that server published them. */
class OAuthService(val issuer:String,val authorization:String,val token:String,val registration:String?)

/** Credentials from the token endpoint. expiresAt is epoch seconds; 0 means the server said nothing. */
class OAuthTokens(val access:String,val refresh:String?,val expiresAt:Long,val scope:String)

/** A sign-in that has been started and is waiting for the browser to come back. */
class PendingSignIn(val server:String,val clientId:String,val verifier:String,val state:String,
                    val tokenEndpoint:String,val authorizationUrl:String) {
 fun encode():String=JSONObject().put("server",server).put("client_id",clientId).put("verifier",verifier)
  .put("state",state).put("token_endpoint",tokenEndpoint).put("authorization_url",authorizationUrl).toString()
 companion object {
  fun decode(raw:String?):PendingSignIn? = raw?.takeIf{it.isNotBlank()}?.let {
   runCatching {
    val json=JSONObject(it)
    PendingSignIn(json.getString("server"),json.getString("client_id"),json.getString("verifier"),
     json.getString("state"),json.getString("token_endpoint"),json.getString("authorization_url"))
   }.getOrNull()
  }
 }
}

interface OAuthTransport {
 fun get(url:String):JSONObject
 fun form(url:String,fields:Map<String,String>):JSONObject
 fun json(url:String,body:JSONObject):JSONObject
}

/**
 * Authorization code with PKCE against the user's own server.
 *
 * The app never sees a password: it opens the server's sign-in page in the browser and only
 * receives a one-time code bound to a verifier that never leaves this device.
 */
class OAuthClient(private val transport:OAuthTransport=HttpOAuthTransport()) {

 fun discover(server:String):OAuthService {
  val base=serverBase(server)
  val document=transport.get("$base/.well-known/oauth-authorization-server")
  val service=OAuthService(document.optString("issuer",base),document.getString("authorization_endpoint"),
   document.getString("token_endpoint"),document.optString("registration_endpoint").takeIf{it.isNotBlank()})
  // Only follow endpoints on the server the user named; a redirected document cannot move them.
  require(sameOrigin(base,service.authorization) && sameOrigin(base,service.token)) { "Server returned unrelated sign-in addresses" }
  service.registration?.let { require(sameOrigin(base,it)) { "Server returned unrelated sign-in addresses" } }
  return service
 }

 fun register(service:OAuthService):String {
  val endpoint=service.registration ?: throw IllegalStateException("This server does not register apps automatically")
  val body=JSONObject().put("client_name",CLIENT_NAME)
   .put("redirect_uris",org.json.JSONArray().put(OAUTH_REDIRECT))
   .put("grant_types",org.json.JSONArray().put("authorization_code").put("refresh_token"))
   .put("response_types",org.json.JSONArray().put("code"))
   .put("token_endpoint_auth_method","none")
  val registered=transport.json(endpoint,body)
  return registered.getString("client_id")
 }

 fun begin(server:String):PendingSignIn {
  val service=discover(server)
  val clientId=register(service)
  val verifier=randomValue();val state=randomValue()
  return PendingSignIn(serverBase(server),clientId,verifier,state,service.token,
   authorizationUrl(service,clientId,verifier,state))
 }

 fun authorizationUrl(service:OAuthService,clientId:String,verifier:String,state:String):String {
  val query=mapOf("response_type" to "code","client_id" to clientId,"redirect_uri" to OAUTH_REDIRECT,
   "scope" to OAUTH_SCOPE,"state" to state,"code_challenge" to challenge(verifier),
   "code_challenge_method" to "S256")
   .map{(k,v)->"${encode(k)}=${encode(v)}"}.joinToString("&")
  return service.authorization+(if(service.authorization.contains('?')) "&" else "?")+query
 }

 fun exchange(pending:PendingSignIn,code:String):OAuthTokens =
  tokens(transport.form(pending.tokenEndpoint,mapOf("grant_type" to "authorization_code","code" to code,
   "client_id" to pending.clientId,"redirect_uri" to OAUTH_REDIRECT,"code_verifier" to pending.verifier)))

 fun refresh(tokenEndpoint:String,clientId:String,refreshToken:String):OAuthTokens =
  tokens(transport.form(tokenEndpoint,mapOf("grant_type" to "refresh_token","refresh_token" to refreshToken,
   "client_id" to clientId)))

 private fun tokens(response:JSONObject):OAuthTokens {
  val access=response.getString("access_token")
  require(access.isNotBlank() && response.optString("token_type").equals("Bearer",ignoreCase=true)) { "Unusable sign-in response" }
  val lifetime=response.optLong("expires_in",0L)
  return OAuthTokens(access,response.optString("refresh_token").takeIf{it.isNotBlank()},
   if(lifetime>0) System.currentTimeMillis()/1000+lifetime else 0L,
   response.optString("scope",OAUTH_SCOPE))
 }

 companion object {
  fun randomValue():String {
   val bytes=ByteArray(32);SecureRandom().nextBytes(bytes)
   return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
  }
  fun challenge(verifier:String):String =
   Base64.getUrlEncoder().withoutPadding().encodeToString(
    MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
  /** The server as an origin: scheme and host only, with no path, query or credentials. */
  fun serverBase(server:String):String {
   val uri=URI(server.trim().trimEnd('/'))
   require(uri.host!=null && uri.userInfo==null && uri.query==null && uri.fragment==null &&
    (uri.scheme=="https" || (BuildConfig.DEBUG && uri.scheme=="http"))) { "Use a valid HTTPS server URL" }
   return server.trim().trimEnd('/')
  }
  fun sameOrigin(base:String,url:String):Boolean = runCatching {
   val a=URI(base);val b=URI(url)
   a.scheme==b.scheme && a.host.equals(b.host,ignoreCase=true) && a.port==b.port && b.userInfo==null
  }.getOrDefault(false)
  internal fun encode(value:String):String=URLEncoder.encode(value,"UTF-8")
 }
}

/** Bounded HTTPS requests to the sign-in endpoints; no redirects are followed. */
class HttpOAuthTransport:OAuthTransport {
 override fun get(url:String):JSONObject = send(url,null,null)
 override fun form(url:String,fields:Map<String,String>):JSONObject =
  send(url,fields.map{(k,v)->"${OAuthClient.encode(k)}=${OAuthClient.encode(v)}"}.joinToString("&"),
   "application/x-www-form-urlencoded")
 override fun json(url:String,body:JSONObject):JSONObject = send(url,body.toString(),"application/json")

 private fun send(url:String,body:String?,contentType:String?):JSONObject {
  val connection=URL(url).openConnection() as HttpURLConnection
  try {
   connection.instanceFollowRedirects=false
   connection.connectTimeout=15000;connection.readTimeout=20000
   connection.setRequestProperty("User-Agent","Euros-Android/${BuildConfig.VERSION_NAME}")
   connection.setRequestProperty("Accept","application/json")
   if(body!=null) {
    connection.requestMethod="POST";connection.doOutput=true
    connection.setRequestProperty("Content-Type",contentType!!)
    connection.outputStream.use{it.write(body.toByteArray())}
   }
   val code=connection.responseCode
   val text=(if(code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use{it.readText()} ?: "{}"
   if(code !in 200..299) throw ApiFailure(code,signInFailureMessage(code,text))
   return JSONObject(text)
  } finally { connection.disconnect() }
 }
}

/** Never show a raw server body or a token; sign-in problems get an action the user can take. */
internal fun signInFailureMessage(code:Int,body:String):String {
 val error=runCatching{JSONObject(body).optString("error")}.getOrDefault("")
 return when {
  error=="invalid_grant" -> "That sign-in expired. Try signing in again"
  error=="invalid_client" || error=="invalid_redirect_uri" -> "This server doesn't recognise this app. Check the server address"
  code==404 -> "That server doesn't offer sign-in. Check the address, or use a server token"
  code==429 -> "Too many attempts. Try again shortly"
  code>=500 -> "The server couldn't complete sign-in. Try again"
  else -> "Couldn't sign in. Check the server address and try again"
 }
}
