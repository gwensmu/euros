package com.gwensmuda.euros

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

class AuthoredPlan(context:Context) {
 private val data=JSONObject(context.assets.open("euros.json").bufferedReader().use{it.readText()})
 fun json()=JSONObject(data.toString())
 val eventDate:LocalDate=LocalDate.parse(data.getJSONObject("event").getString("date"))
 fun day(date:LocalDate,records:List<Record>,includeNutrition:Boolean=true)=DayPlanComposer.compose(data,records,date,includeNutrition)
 fun foodDay(date:LocalDate,records:List<Record>)=DayPlanComposer.food(data,records,date)
 fun mealReferences(date:LocalDate,records:List<Record>)=FoodDomain.mealReferences(data,date,records)
}
