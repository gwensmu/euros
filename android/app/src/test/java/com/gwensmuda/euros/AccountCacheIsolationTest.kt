package com.gwensmuda.euros

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=android.app.Application::class)
class AccountCacheIsolationTest {
 private class AccountApi(override val accountId:String):SyncApi {
  override val identity="https://training.example"
  val calls=mutableListOf<String>()
  override fun request(path:String,body:JSONObject?):JSONObject {
   calls+=path
   check(path.startsWith("/v1/changes")) { "No writes expected" }
   return JSONObject("""{"changes":[],"next_cursor":0,"has_more":false}""")
  }
 }
 @Test fun anotherAccountCannotPullIntoOrUploadFromTheExistingCache()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  try {
   val repo=Repository(db);val first=AccountApi("usr-"+"a".repeat(32));val second=AccountApi("usr-"+"b".repeat(32))
   repo.sync(first)
   repo.saveDraft("food:private","Private food draft")
   val error=runCatching{repo.sync(second)}.exceptionOrNull()
   assertTrue(error is IllegalStateException)
   assertTrue(second.calls.isEmpty())
   assertEquals(first.accountId,repo.dao.meta("server_account"))
   assertEquals("Private food draft",repo.loadDraft("food:private"))
   repo.sync(first)
  } finally {db.close()}
 }
 @Test fun legacyCacheCannotBeClaimedByAnArbitraryNewAccount()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  try {
   val repo=Repository(db);val other=AccountApi("usr-"+"b".repeat(32))
   repo.dao.meta(Metadata("server_identity",other.identity))
   assertTrue(runCatching{repo.sync(other)}.isFailure)
   assertTrue(other.calls.isEmpty());assertNull(repo.dao.meta("server_account"))
   val legacy=AccountApi("usr-"+"0".repeat(32));repo.sync(legacy)
   assertEquals(legacy.accountId,repo.dao.meta("server_account"))
  } finally {db.close()}
 }
 @Test fun populatedUnboundCacheCannotUploadToANewAccount()=runBlocking {
  val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),EurosDatabase::class.java).build()
  try {
   val repo=Repository(db);val other=AccountApi("usr-"+"b".repeat(32))
   repo.saveDraft("food:private","Unsynced private draft")
   assertTrue(runCatching{repo.sync(other)}.isFailure)
   assertTrue(other.calls.isEmpty());assertNull(repo.dao.meta("server_account"))
   assertEquals("Unsynced private draft",repo.loadDraft("food:private"))
  } finally {db.close()}
 }
 @Test fun anOldModelClientCannotBorrowANewAccountsCredentials() {
  var value=ServerEnrollment("https://training.example","access-a","refresh-a",0,"client-a","https://training.example/oauth/token")
  val store=object:EnrollmentStore {
   override fun read()=value
   override fun write(enrollment:ServerEnrollment){value=enrollment}
   override fun clear()=Unit
  }
  val tokens=EnrollmentTokens(store,expectedClientId="client-a")
  assertEquals("access-a",tokens.current())
  value=ServerEnrollment(value.server,"access-b","refresh-b",0,"client-b",value.tokenEndpoint)
  assertTrue(runCatching{tokens.current()}.isFailure)
  assertTrue(runCatching{tokens.refreshed("access-a")}.isFailure)
 }
}
