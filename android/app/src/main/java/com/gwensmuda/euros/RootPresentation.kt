package com.gwensmuda.euros

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

internal enum class RootTab { Today, Week, Food }

fun weekTitle(start:LocalDate):String {
 val end=start.plusDays(6)
 return if(start.month==end.month) "${start.format(DateTimeFormatter.ofPattern("MMMM d"))}–${end.dayOfMonth}" else "${start.format(DateTimeFormatter.ofPattern("MMM d"))} – ${end.format(DateTimeFormatter.ofPattern("MMM d"))}"
}
@Composable internal fun EurosNavigation(tab:RootTab,onSelect:(RootTab)->Unit) {
 Column(Modifier.fillMaxWidth().background(EurosColors.Paper).navigationBarsPadding()) {
  HorizontalDivider(color=EurosColors.Line)
  Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
   RootTab.entries.forEach { name ->
    val selected=tab==name
    Surface(onClick={onSelect(name)},color=if(selected)EurosColors.Soft else Color.Transparent,shape=MaterialTheme.shapes.small,modifier=Modifier.weight(1f).semantics{this.selected=selected}) {
     Column(Modifier.padding(vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
      Icon(when(name){RootTab.Today->Icons.Outlined.WbSunny;RootTab.Week->Icons.Outlined.CalendarMonth;else->Icons.Outlined.Restaurant},null,Modifier.size(20.dp),tint=if(selected)EurosColors.Blue else EurosColors.Ink)
      Text(name.name,style=MaterialTheme.typography.labelMedium,color=if(selected)EurosColors.Blue else EurosColors.Ink)
     }
    }
   }
  }
 }
}

@Composable internal fun EurosHeader(onSettings:()->Unit) {
 Row(Modifier.fillMaxWidth().padding(horizontal=22.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
    Text("EUROS / 27",color=Color.White,letterSpacing=2.sp,style=MaterialTheme.typography.labelMedium,modifier=Modifier.background(EurosColors.Ink).padding(horizontal=10.dp,vertical=6.dp))
    OutlinedIconButton(onClick=onSettings,shape=MaterialTheme.shapes.small,border=BorderStroke(1.dp,EurosColors.Line)){Icon(Icons.Outlined.Settings,"Settings",Modifier.size(20.dp))}
   }
 HorizontalDivider(thickness=2.dp,color=Color(0xFF73A8FF))
}

@Composable internal fun PageTitle(tab:RootTab,today:LocalDate,week:LocalDate,foodDay:LocalDate,mascotPose:MascotPose=MascotPose.IDLE) {
 Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
      Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
       if(tab==RootTab.Today) Text(today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")).uppercase(),style=MaterialTheme.typography.labelSmall,color=EurosColors.Muted,letterSpacing=1.sp)
       Text(when(tab){RootTab.Today->"${ChronoUnit.DAYS.between(today,LocalDate.of(2027,1,13)).coerceAtLeast(0)} days to Euros";RootTab.Week->weekTitle(week);else->foodDay.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))},style=if(tab==RootTab.Today) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall)
      }
      if(tab==RootTab.Today) Weasel(mascotPose)
     }
}
