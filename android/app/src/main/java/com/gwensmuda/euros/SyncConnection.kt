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
class ServerEnrollment(val server:String,val token:String)
interface EnrollmentStore { fun read():ServerEnrollment?; fun write(value:ServerEnrollment); fun clear() }

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
  return ServerEnrollment(value.getString("server"),value.getString("token"))
 }
 override fun write(value:ServerEnrollment) {
  val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
  val bytes=JSONObject().put("server",value.server).put("token",value.token).toString().toByteArray(Charsets.UTF_8)
  val output=file.startWrite()
  try{output.write(cipher.iv);output.write(cipher.doFinal(bytes));file.finishWrite(output)}catch(e:Exception){file.failWrite(output);throw e}
 }
 override fun clear(){file.delete();KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.deleteEntry(alias)}
}

data class ConnectionState(val connected:Boolean=false,val server:String="",val syncing:Boolean=false,val lastSuccess:String?=null,val error:String?=null)
enum class SyncOutcome { SUCCESS, RETRY, RECONNECT }
interface SyncScheduling { fun enable(); fun request(); fun cancel() }

/** One gate covers enrollment, background requests and disconnect, including in-flight requests. */
class SyncConnection(
 private val repo:Repository,private val store:EnrollmentStore,private val scheduling:SyncScheduling,
 private val apiFactory:(ServerEnrollment)->SyncApi={Api(it.server,it.token)},
 private val reconcile:suspend ()->Unit={},
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
   mutableState.value=ConnectionState(enrollment!=null,enrollment?.server ?: "",lastSuccess=repo.dao.meta("last_sync_success"))
   enrollment?.let{repo.connectInterpretation(apiFactory(it));scheduling.enable()}
  }catch(e:CancellationException){initialized=false;enrollment=null;repo.disconnectInterpretation();throw e}
  catch(_:Exception){repo.disconnectInterpretation();mutableState.value=ConnectionState(error="Reconnect in Settings to restore your connection")}
 }
 suspend fun initialize()=gate.withLock{initializeLocked()}
 suspend fun connect(server:String,token:String)=gate.withLock {
  initializeLocked()
  val candidate=ServerEnrollment(server.trim().trimEnd('/'),token.trim())
  require(candidate.server.isNotBlank() && candidate.token.isNotBlank())
  mutableState.value=mutableState.value.copy(syncing=true,error=null)
  try {
   repo.sync(apiFactory(candidate))
   store.write(candidate)
   enrollment=candidate
   mutableState.value=mutableState.value.copy(connected=true,server=candidate.server)
   scheduling.enable();scheduling.request()
   success(candidate)
  }catch(e:CancellationException){
   enrollment?.let{repo.connectInterpretation(apiFactory(it))} ?: repo.disconnectInterpretation()
   throw e
  }
  catch(e:Exception){
   // A failed enrollment must not leave a newly authenticated model client behind.
   enrollment?.let{repo.connectInterpretation(apiFactory(it))} ?: repo.disconnectInterpretation()
   mutableState.value=mutableState.value.copy(error=syncFailureMessage(e));throw e
  }finally{mutableState.value=mutableState.value.copy(syncing=false)}
 }
 suspend fun sync():SyncOutcome=gate.withLock {
  initializeLocked()
  val saved=enrollment ?: return@withLock SyncOutcome.RECONNECT
  mutableState.value=mutableState.value.copy(syncing=true,error=null)
  try{repo.sync(apiFactory(saved));success(saved);SyncOutcome.SUCCESS}
  catch(e:CancellationException){throw e}
  catch(e:Exception){
   if(e is ApiFailure && e.status in setOf(401,403))repo.disconnectInterpretation()
   mutableState.value=mutableState.value.copy(error=syncFailureMessage(e))
   if(e is ApiFailure && e.status in setOf(401,403))SyncOutcome.RECONNECT else SyncOutcome.RETRY
  }finally{mutableState.value=mutableState.value.copy(syncing=false)}
 }
 private suspend fun success(saved:ServerEnrollment) {
  val now=Instant.now().toString();repo.dao.meta(Metadata("last_sync_success",now))
  repo.dao.meta(Metadata("server",saved.server))
  mutableState.value=ConnectionState(true,saved.server,true,now)
  try{reconcile()}catch(e:CancellationException){throw e}catch(_:Exception){mutableState.value=mutableState.value.copy(error="Synced. Couldn't update reminders")}
 }
 suspend fun disconnect()=gate.withLock {
  initializeLocked();store.clear();enrollment=null;repo.disconnectInterpretation();scheduling.cancel()
  mutableState.value=ConnectionState(lastSuccess=mutableState.value.lastSuccess)
 }
 fun request(){if(mutableState.value.connected)scheduling.request()}
}

internal fun syncFailureMessage(error:Exception):String=when(error) {
 is ApiFailure -> backendErrorMessage(error.status,"")
 is java.io.IOException -> "Couldn't reach your server. Changes stay on this phone and will retry."
 else -> "Couldn't sync. Your local changes are saved. Check the connection and retry."
}
