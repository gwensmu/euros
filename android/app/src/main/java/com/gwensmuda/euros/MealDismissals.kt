package com.gwensmuda.euros

import org.json.JSONArray

/** Date-scoped local presentation state; never food intake, a plan edit, or an outcome. */
object MealDismissals {
 fun decode(raw:String?):Set<String> {
  if(raw==null)return emptySet()
  val values=JSONArray(raw)
  return (0 until values.length()).map{values.getString(it)}.toSet()
 }
}
