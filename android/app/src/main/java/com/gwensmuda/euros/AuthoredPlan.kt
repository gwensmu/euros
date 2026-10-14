package com.gwensmuda.euros

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

class AuthoredPlan(context:Context) {
 private val data=JSONObject(context.assets.open("euros.json").bufferedReader().use{it.readText()})
 fun json()=JSONObject(data.toString())
 fun day(date:LocalDate,records:List<Record>)=DayPlanComposer.compose(data,records,date)
}
