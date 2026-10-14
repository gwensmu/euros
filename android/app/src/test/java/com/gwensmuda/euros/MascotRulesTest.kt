package com.gwensmuda.euros
import org.junit.Test
import org.junit.Assert.*
class MascotRulesTest {
 private fun r(status:String)=Record("activities","one",1,"{\"status\":\"$status\"}")
 @Test fun unknownIsNeutral(){assertEquals("neutral",MascotRules.mood(emptyList(),emptyList()));val r=r("pending");assertEquals("neutral",MascotRules.mood(listOf(r),listOf(r)))}
 @Test fun skippedIsRestingNotFailure(){val r=r("skipped");assertEquals("resting",MascotRules.mood(listOf(r),listOf(r)))}
 @Test fun planCompletionIsPleased(){val r=r("done");assertEquals("pleased",MascotRules.mood(listOf(r),listOf(r)))}
 @Test fun foodRestrictionDoesNotAffectMood(){val food=Record("food_entries","meal",1,"{}");assertEquals("neutral",MascotRules.mood(emptyList(),listOf(food)))}
}
