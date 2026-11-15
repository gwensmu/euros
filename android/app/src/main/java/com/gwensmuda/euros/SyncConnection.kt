package com.gwensmuda.euros

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Deliberately not a data class: credentials must never appear in generated toString output.
// An account sign-in carries a refresh token and the endpoint that renews it; a server token
// carries neither and is used until it is replaced by hand.
class ServerEnrollment(val server:String,val token:String,val refreshToken:String?=null,
                       val expiresAt:Long=0L,val clientId:String?=null,val tokenEndpoint:String?=null,
                       val account:String?=null) {
 val renewable:Boolean get() = refreshToken!=null && clientId!=null && tokenEndpoint!=null
 fun renewed(tokens:OAuthTokens)=ServerEnrollment(server,tokens.access,tokens.refresh ?: refreshToken,
  tokens.expiresAt,clientId,tokenEndpoint,account)
}
interface EnrollmentStore { fun read():ServerEnrollment?; fun write(value:ServerEnrollment); fun clear() }

/**
 * The access token for a signed-in account, renewed when the server rejects it.
 *
 * Always reads the stored enrollment, never a snapshot: refresh tokens rotate, so presenting an
 * old one would end the account's grant on the server.
 */
class EnrollmentTokens(private val store:EnrollmentStore,private val client:OAuthClient=OAuthClient(),private val expectedClientId:String?=null):TokenSource {
 private val lock=Any()
 private fun enrollment():ServerEnrollment? = store.read()?.also {
  check(expectedClientId==null || it.clientId==expectedClientId) { "Account connection changed. Try again." }
 }
 override fun current():String = enrollment()?.token ?: error("Sign in again in Settings")
 override fun refreshed(expired:String):String? = synchronized(lock) {
  val saved=enrollment() ?: return null
  if(saved.token!=expired) return saved.token
  if(!saved.renewable) return null
  val tokens=client.refresh(saved.tokenEndpoint!!,saved.clientId!!,saved.refreshToken!!)
  store.write(saved.renewed(tokens))
  tokens.access
 }
}

/** Ciphertext is atomic, private and excluded from backup; the non-exportable key stays in Keystore. */
class SecureEnrollmentStore(context:Context):EnrollmentStore {
 private val file=AtomicFile(File(context.noBackupFilesDir,"server-enrollment.enc"))
 private val alias="euros.server.enrollment.v1"
 private fun key():SecretKey {
  val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
  return (store.getKey(alias,null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
   init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
  }.generateKey()
 }
 override fun read():ServerEnrollment? {
  val bytes=try{file.readFully()}catch(_:java.io.FileNotFoundException){return null}
  require(bytes.size>28)
  val cipher=Cipher.getInstance("AES/GCM/NoPadding")
  cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
  val value=JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
  // Enrollments written before accounts existed hold only a server token; they stay valid.
  return ServerEnrollment(value.getString("server"),value.getString("token"),
   value.optString("refresh_token").takeIf{it.isNotBlank()},value.optLong("expires_at",0L),
   value.optString("client_id").takeIf{it.isNotBlank()},value.optString("token_endpoint").takeIf{it.isNotBlank()},
   value.optString("account").takeIf{it.isNotBlank()})
 }
 override fun write(value:ServerEnrollment) {
  val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
  val bytes=JSONObject().put("server",value.server).put("token",value.token)
   .put("refresh_token",value.refreshToken ?: JSONObject.NULL).put("expires_at",value.expiresAt)
   .put("client_id",value.clientId ?: JSONObject.NULL).put("token_endpoint",value.tokenEndpoint ?: JSONObject.NULL)
   .put("account",value.account ?: JSONObject.NULL).toString().toByteArray(Charsets.UTF_8)
  val output=file.startWrite()
  try{output.write(cipher.iv);output.write(cipher.doFinal(bytes));file.finishWrite(output)}catch(e:Exception){file.failWrite(output);throw e}
 }
 override fun clear(){file.delete();KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry(alias)}
}

data class ConnectionState(val connected:Boolean=false,val server:String="",val syncing:Boolean=false,val lastSuccess:String?=null,val error:String?=null,val account:String?=null)
enum class SyncOutcome { SUCCESS, RETRY, RECONNECT }
interface SyncScheduling { fun enable(); fun request(); fun cancel() }

/** One gate covers enrollment, background requests and disconnect, including in-flight requests. */
class SyncConnection(
 private val repo:Repository,private val store:EnrollmentStore,private val scheduling:SyncScheduling,
 private val apiFactory:(ServerEnrollment)->SyncApi={enrolled->
  Api(enrolled.server,if(enrolled.renewable)EnrollmentTokens(store,expectedClientId=enrolled.clientId) else StaticToken(enrolled.token))},
 private val reconcile:suspend ()->Unit={},
 private val oauth:OAuthClient=OAuthClient(),
) {
 private val gate=Mutex()
 private var initialized=false
 private var enrollment:ServerEnrollment?=null
 private val mutableState=MutableStateFlow(ConnectionState())
 val state:StateFlow<ConnectionState> = mutableState
 private suspend fun initializeLocked() {
  if(initialized)return
  initialized=true
  try {
   enrollment=store.read()
   mutableState.value=ConnectionState(enrollment!=null,enrollment?.server ?: "",
    lastSuccess=repo.dao.meta("last_sync_success"),account=enrollment?.account)
   enrollment?.let{repo.connectInterpretation(apiFactory(it));scheduling.enable()}
  }catch(e:CancellationException){initialized=false;enrollment=null;repo.disconnectInterpretation();throw e}
  catch(_:Exception){repo.disconnectInterpretation();mutableState.value=ConnectionState(error="Reconnect in Settings to restore your connection")}
 }
 suspend fun initialize()=gate.withLock{initializeLocked()}

 /** Enroll with a server token, for a server that has not moved to accounts. */
 suspend fun connect(server:String,token:String)=gate.withLock {
  initializeLocked()
  val candidate=ServerEnrollment(server.trim().trimEnd('/'),token.trim())
  require(candidate.server.isNotBlank() && candidate.token.isNotBlank())
  enroll(candidate)
 }

 /**
  * Start signing in to an account: ask the server where its sign-in page is, register this app
  * and remember the one-time verifier. The browser does the rest; nothing is enrolled yet.
  */
 suspend fun beginSignIn(server:String):PendingSignIn {
  val pending=oauth.begin(server)
  repo.dao.meta(Metadata(SIGN_IN_KEY,pending.encode()))
  return pending
 }

 /**
  * Finish a sign-in the browser handed back.
  *
  * The state must match the request this device started, so another app's redirect cannot
  * enroll an account here. Nothing is kept unless the first sync with the new tokens succeeds.
  */
 suspend fun completeSignIn(redirect:android.net.Uri):Boolean {
  val pending=PendingSignIn.decode(repo.dao.meta(SIGN_IN_KEY)) ?: return false
  val code=redirect.getQueryParameter("code")
  val state=redirect.getQueryParameter("state")
  val refusal=redirect.getQueryParameter("error")
  if(state==null || state!=pending.state) return false
  repo.dao.meta(Metadata(SIGN_IN_KEY,""))
  if(code==null) {
   mutableState.value=mutableState.value.copy(error=
    if(refusal=="access_denied") "Sign-in was cancelled" else "Sign-in didn't complete. Try again")
   return false
  }
  val tokens=try { oauth.exchange(pending,code) }
  catch(e:CancellationException){ throw e }
  catch(e:Exception) {
   mutableState.value=mutableState.value.copy(error=if(e is ApiFailure) e.message else "Couldn't finish signing in. Try again")
   return false
  }
  gate.withLock {
   initializeLocked()
   enroll(ServerEnrollment(pending.server,tokens.access,tokens.refresh,tokens.expiresAt,
    pending.clientId,pending.tokenEndpoint))
  }
  return true
 }

 /** Caller holds the gate. Saves first, because a signed-in account's token is read back from storage. */
 private suspend fun enroll(candidate:ServerEnrollment) {
  val previous=enrollment
  mutableState.value=mutableState.value.copy(syncing=true,error=null)
  try {
   store.write(candidate)
   enrollment=candidate
   repo.sync(apiFactory(candidate))
   mutableState.value=mutableState.value.copy(connected=true,server=candidate.server,account=candidate.account)
   scheduling.enable();scheduling.request()
   success(candidate)
   publishWorkouts(apiFactory(candidate))
  }catch(e:CancellationException){ restore(previous);throw e }
  catch(e:Exception){
   // A failed enrollment must not leave credentials or a newly authenticated model client behind.
   restore(previous)
   mutableState.value=mutableState.value.copy(error=syncFailureMessage(e));throw e
  }finally{mutableState.value=mutableState.value.copy(syncing=false)}
 }

 private fun restore(previous:ServerEnrollment?) {
  enrollment=previous
  runCatching {
   if(previous!=null){store.write(previous);repo.connectInterpretation(apiFactory(previous))}
   else {store.clear();repo.disconnectInterpretation()}
  }.onFailure{repo.disconnectInterpretation()}
 }
 suspend fun sync():SyncOutcome=gate.withLock {
  initializeLocked()
  val saved=enrollment ?: return@withLock SyncOutcome.RECONNECT
  mutableState.value=mutableState.value.copy(syncing=true,error=null)
  try{repo.sync(apiFactory(saved));success(saved);publishWorkouts(apiFactory(saved))}
  catch(e:CancellationException){throw e}
  catch(e:Exception){
   if(e is ApiFailure && e.status in setOf(401,403))repo.disconnectInterpretation()
   mutableState.value=mutableState.value.copy(error=syncFailureMessage(e))
   if(e is ApiFailure && e.status in setOf(401,403))SyncOutcome.RECONNECT else SyncOutcome.RETRY
  }finally{mutableState.value=mutableState.value.copy(syncing=false)}
 }
 private suspend fun publishWorkouts(api:SyncApi):SyncOutcome {
  return try{TrackerPublisher(repo.dao).publish(api);SyncOutcome.SUCCESS}
  catch(e:CancellationException){throw e}
  catch(_:TrackerPublicationConflict) {
   mutableState.value=mutableState.value.copy(error="Logs synced. Workout sync needs conflict review; local workout data is preserved.")
   // A blocked durable envelope requires explicit reconciliation, never an automatic overwrite.
   SyncOutcome.SUCCESS
  }
  catch(_:Exception) {
   mutableState.value=mutableState.value.copy(error="Logs synced. Workout sync couldn't finish and will retry.")
   SyncOutcome.RETRY
  }
 }
 private suspend fun success(saved:ServerEnrollment) {
  val now=Instant.now().toString();repo.dao.meta(Metadata("last_sync_success",now))
  repo.dao.meta(Metadata("server",saved.server))
  val named=AccountPlan.refresh(repo,apiFactory(saved)) ?: saved.account
  if(named!=saved.account && named!=null) {
   // The account behind these credentials, as the server names it; shown, never used as identity.
   val renamed=ServerEnrollment(saved.server,saved.token,saved.refreshToken,saved.expiresAt,
    saved.clientId,saved.tokenEndpoint,named)
   runCatching{store.write(renamed)}.onSuccess{enrollment=renamed}
  }
  mutableState.value=ConnectionState(true,saved.server,true,now,account=named)
  try{reconcile()}catch(e:CancellationException){throw e}catch(_:Exception){mutableState.value=mutableState.value.copy(error="Synced. Couldn't update reminders")}
 }
 suspend fun disconnect()=gate.withLock {
  initializeLocked();store.clear();enrollment=null;repo.disconnectInterpretation();scheduling.cancel()
  repo.dao.meta(Metadata(SIGN_IN_KEY,""));repo.dao.meta(Metadata(AccountPlan.KEY,""))
  mutableState.value=ConnectionState(lastSuccess=mutableState.value.lastSuccess)
 }
 fun request(){if(mutableState.value.connected)scheduling.request()}
 companion object { const val SIGN_IN_KEY="oauth_sign_in" }
}

class AccountCacheMismatch(val migrationRequired:Boolean=false):IllegalStateException()

internal fun syncFailureMessage(error:Exception):String=when(error) {
 is AccountCacheMismatch -> if(error.migrationRequired) "This older local cache needs an account migration. No logs were uploaded." else "These local records belong to another account. Sign in to the original account; pending logs are preserved."
 is ApiFailure -> backendErrorMessage(error.status,"")
 is java.io.IOException -> "Couldn't reach your server. Changes stay on this phone and will retry."
 else -> "Couldn't sync. Your local changes are saved. Check the connection and retry."
}
