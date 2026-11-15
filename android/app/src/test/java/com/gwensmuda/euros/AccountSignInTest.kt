package com.gwensmuda.euros

import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountSignInTest {
 private val server="https://euros.example"
 private fun metadata(prefix:String=server)=JSONObject()
  .put("issuer",prefix).put("authorization_endpoint","$prefix/oauth/authorize")
  .put("token_endpoint","$prefix/oauth/token").put("registration_endpoint","$prefix/oauth/register")
 private fun tokenResponse()=JSONObject().put("access_token","access-1").put("refresh_token","refresh-1")
  .put("token_type","Bearer").put("expires_in",3600).put("scope",OAUTH_SCOPE)

 private class FakeTransport(val metadata:JSONObject,val registration:JSONObject,val tokens:JSONObject):OAuthTransport {
  val forms=mutableListOf<Pair<String,Map<String,String>>>()
  var registrations=0
  override fun get(url:String):JSONObject = metadata
  override fun form(url:String,fields:Map<String,String>):JSONObject { forms+=url to fields;return tokens }
  override fun json(url:String,body:JSONObject):JSONObject { registrations++;return registration }
 }

 private fun transport(metadata:JSONObject=metadata())=
  FakeTransport(metadata,JSONObject().put("client_id","cli-1"),tokenResponse())

 @Test fun verifierIsProvedWithTheStandardS256Challenge() {
  // RFC 7636 appendix B.
  assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
   OAuthClient.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
  val verifier=OAuthClient.randomValue()
  assertTrue(verifier.length in 43..128)
  assertNotEquals(verifier,OAuthClient.randomValue())
 }

 @Test fun signInRegistersThisAppAndAsksForACodeBoundToThisDevice() {
  val transport=transport()
  val pending=OAuthClient(transport).begin("$server/")
  assertEquals(1,transport.registrations)
  assertEquals(server,pending.server)
  assertEquals("$server/oauth/token",pending.tokenEndpoint)
  val url=Uri.parse(pending.authorizationUrl)
  assertEquals("$server/oauth/authorize",url.buildUpon().clearQuery().build().toString())
  assertEquals("code",url.getQueryParameter("response_type"))
  assertEquals("cli-1",url.getQueryParameter("client_id"))
  assertEquals(OAUTH_REDIRECT,url.getQueryParameter("redirect_uri"))
  assertEquals("S256",url.getQueryParameter("code_challenge_method"))
  assertEquals(OAuthClient.challenge(pending.verifier),url.getQueryParameter("code_challenge"))
  assertEquals(pending.state,url.getQueryParameter("state"))
  assertEquals(OAUTH_SCOPE,url.getQueryParameter("scope"))
  assertFalse(pending.authorizationUrl.contains(pending.verifier))
 }

 @Test fun signInEndpointsMustBelongToTheServerTheUserNamed() {
  for(elsewhere in listOf(metadata("https://evil.example"),
   metadata().put("token_endpoint","https://evil.example/oauth/token"),
   metadata().put("registration_endpoint","http://euros.example/oauth/register"))) {
   assertTrue(runCatching{OAuthClient(transport(elsewhere)).begin(server)}.isFailure)
  }
  for(address in listOf("not a url","https://euros.example/path","https://user:pw@euros.example")) {
   assertTrue(runCatching{OAuthClient.serverBase(address)}.isFailure ||
    OAuthClient.serverBase(address)==address.trimEnd('/'))
  }
 }

 @Test fun exchangeSendsTheVerifierAndKeepsTheReturnedTokens() {
  val transport=transport()
  val client=OAuthClient(transport)
  val pending=client.begin(server)
  val tokens=client.exchange(pending,"code-1")
  val (url,fields)=transport.forms.single()
  assertEquals("$server/oauth/token",url)
  assertEquals("authorization_code",fields["grant_type"])
  assertEquals("code-1",fields["code"]);assertEquals(pending.verifier,fields["code_verifier"])
  assertEquals(OAUTH_REDIRECT,fields["redirect_uri"]);assertEquals("cli-1",fields["client_id"])
  assertEquals("access-1",tokens.access);assertEquals("refresh-1",tokens.refresh)
  assertTrue(tokens.expiresAt>System.currentTimeMillis()/1000)
 }

 @Test fun anUnusableTokenResponseIsRefused() {
  val unusable=FakeTransport(metadata(),JSONObject().put("client_id","cli-1"),
   JSONObject().put("access_token","access-1").put("token_type","mac"))
  val client=OAuthClient(unusable)
  val pending=client.begin(server)
  assertTrue(runCatching{client.exchange(pending,"code-1")}.isFailure)
 }

 @Test fun pendingSignInSurvivesProcessDeathAndRejectsNonsense() {
  val pending=OAuthClient(transport()).begin(server)
  val restored=PendingSignIn.decode(pending.encode())!!
  assertEquals(pending.verifier,restored.verifier);assertEquals(pending.state,restored.state)
  assertEquals(pending.clientId,restored.clientId);assertEquals(pending.tokenEndpoint,restored.tokenEndpoint)
  assertNull(PendingSignIn.decode(null));assertNull(PendingSignIn.decode(""));assertNull(PendingSignIn.decode("{"))
 }

 private class MemoryStore(var value:ServerEnrollment?=null):EnrollmentStore {
  var writes=0
  override fun read()=value
  override fun write(value:ServerEnrollment){writes++;this.value=value}
  override fun clear(){value=null}
 }

 @Test fun anExpiredAccessTokenIsRenewedOnceAndStored() {
  val transport=transport()
  val store=MemoryStore(ServerEnrollment(server,"old-access","refresh-1",0,"cli-1","$server/oauth/token"))
  val tokens=EnrollmentTokens(store,OAuthClient(transport))
  assertEquals("old-access",tokens.current())
  assertEquals("access-1",tokens.refreshed("old-access"))
  assertEquals("access-1",store.value!!.token)
  assertEquals("refresh-1",store.value!!.refreshToken)
  assertEquals(mapOf("grant_type" to "refresh_token","refresh_token" to "refresh-1","client_id" to "cli-1"),
   transport.forms.single().second)
  // A token another request already replaced is not spent again: rotation would end the grant.
  assertEquals("access-1",tokens.refreshed("old-access"))
  assertEquals(1,transport.forms.size)
 }

 @Test fun aServerTokenEnrollmentNeverRefreshes() {
  val transport=transport()
  val store=MemoryStore(ServerEnrollment(server,"server-token"))
  val tokens=EnrollmentTokens(store,OAuthClient(transport))
  assertNull(tokens.refreshed("server-token"))
  assertTrue(transport.forms.isEmpty())
  assertNull(StaticToken("server-token").refreshed("server-token"))
  assertEquals("server-token",StaticToken("server-token").current())
 }

 @Test fun onlyThisAppsOwnRedirectIsAccepted() {
  val redirect=Intent(Intent.ACTION_VIEW,Uri.parse("$OAUTH_REDIRECT?code=abc&state=xyz"))
  val consumed=consumeSignInRedirect(redirect)
  assertEquals("abc",consumed!!.getQueryParameter("code"))
  assertNull(consumeSignInRedirect(redirect))
  assertNull(consumeSignInRedirect(Intent(Intent.ACTION_VIEW,Uri.parse("https://euros.example/oauth2redirect?code=abc"))))
  assertNull(consumeSignInRedirect(Intent(Intent.ACTION_VIEW,Uri.parse("com.gwensmuda.euros:/other?code=abc"))))
  assertNull(consumeSignInRedirect(Intent(Intent.ACTION_MAIN)))
 }

 @Test fun signInProblemsNeverShowAServerBody() {
  val body="""{"error":"invalid_grant","error_description":"token eyJhbGciOi secret"}"""
  val message=signInFailureMessage(400,body)
  assertFalse(message.contains("eyJhbGciOi"))
  assertEquals("That sign-in expired. Try signing in again",message)
  assertFalse(signInFailureMessage(500,"stack trace with secret").contains("secret"))
 }

 @Test fun cachedAccountPlanProvidesTheCompetitionUntilItIsReplaced() {
  val raw="""{"revision":1,"source":"user","plan":{"competition":{"name":"Pan Ams","date":"2027-03-04"}}}"""
  val plan=AccountPlan.parse(raw)
  assertEquals(java.time.LocalDate.of(2027,3,4),AccountPlan.eventDate(plan))
  assertEquals("Pan Ams",AccountPlan.eventName(plan))
  assertNull(AccountPlan.parse(null));assertNull(AccountPlan.parse(""));assertNull(AccountPlan.parse("{"))
  assertNull(AccountPlan.eventDate(AccountPlan.parse("""{"plan":{"competition":{"name":"X","date":"soon"}}}""")))
  assertEquals("https://euros.example/account/",AccountPlan.accountSite("https://euros.example/"))
  assertNull(AccountPlan.accountSite(""))
 }
}
