package com.gwensmuda.euros

import java.time.Duration

/** Personal Polar screenshot confirmed October 14, 2026. No HRmax or vendor totals inferred.
 * Lower inclusive / upper exclusive: <95 and >=190 are separate from Zones 1–5.
 * Estimated previous-sample hold across gaps <=60s, with no endpoint extrapolation.
 */
object HeartRateZones {
 val boundaries=listOf(95L,114L,133L,152L,171L,190L)
 fun recap(session:ImportedSession,samples:List<HeartSample>):SessionRecap {
  val unique=HealthSnapshots.deduplicateSamples(samples.filter{it.time>=session.start && it.time<=session.end})
  val zones=MutableList(7){0.0};var covered=0.0;var weighted=0.0
  unique.zipWithNext().forEach{(a,b)->
   val duration=Duration.between(a.time,b.time)
   if(!duration.isNegative && !duration.isZero && duration<=Duration.ofSeconds(60)) {
    val seconds=duration.seconds+duration.nano/1e9
    covered+=seconds;weighted+=a.bpm*seconds
    zones[boundaries.count{a.bpm>=it}]+=seconds
   }
  }
  return SessionRecap(session,unique,covered,if(covered>0)weighted/covered else null,unique.maxOfOrNull{it.bpm},zones)
 }
}
