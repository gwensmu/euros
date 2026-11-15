package com.gwensmuda.euros

import org.json.JSONObject
import java.time.LocalDate

/**
 * The signed-in account's own training plan, as the server last reported it.
 *
 * The user sets this on the account site; the app only reads it, caches the last answer and
 * falls back to the bundled authored plan when there is nothing cached. A cached plan is a
 * display input, never a record: it changes no log, target snapshot or scheduled activity.
 */
object AccountPlan {
 const val KEY="account_plan"

 /** Refresh the cache after a successful sync; returns the account's display name when known. */
 suspend fun refresh(repo:Repository,api:SyncApi):String? = runCatching {
  val plan=api.request("/v1/plan")
  require(plan.has("plan"))
  repo.dao.meta(Metadata(KEY,plan.toString()))
  plan.optJSONObject("account")?.let{ account ->
   account.optString("display_name").takeIf{it.isNotBlank()} ?: account.optString("username").takeIf{it.isNotBlank()}
  }
 }.getOrNull()

 fun parse(raw:String?):JSONObject? =
  raw?.takeIf{it.isNotBlank()}?.let{ runCatching{JSONObject(it).getJSONObject("plan")}.getOrNull() }

 fun eventDate(plan:JSONObject?):LocalDate? =
  plan?.optJSONObject("competition")?.optString("date")?.takeIf{it.isNotBlank()}
   ?.let{ runCatching{LocalDate.parse(it)}.getOrNull() }

 fun eventName(plan:JSONObject?):String? =
  plan?.optJSONObject("competition")?.optString("name")?.takeIf{it.isNotBlank()}

 /** Where the account site for this server is, so Settings can send the user to edit the plan. */
 fun accountSite(server:String?):String? = server?.takeIf{it.isNotBlank()}?.trimEnd('/')?.plus("/account/")
}
