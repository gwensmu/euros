package com.gwensmuda.euros

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Calendar progress toward the event, independent of logged training. */
object CompetitionCountdown {
 fun fraction(start:LocalDate,event:LocalDate,today:LocalDate):Float {
  if(!today.isBefore(event))return 1f
  val total=ChronoUnit.DAYS.between(start,event)
  if(total<=0)return 0f
  return (ChronoUnit.DAYS.between(start,today).toDouble()/total).coerceIn(0.0,1.0).toFloat()
 }
}
