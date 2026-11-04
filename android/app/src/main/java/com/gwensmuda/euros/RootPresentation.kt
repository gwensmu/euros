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
 Column(Modifier.fillMaxWidth().background(Color.White)) {
  HorizontalDivider(color=EurosColors.Line)
  NavigationBar(containerColor=Color.White,tonalElevation=0.dp) {
   RootTab.entries.forEach { name ->
    NavigationBarItem(selected=tab==name,onClick={onSelect(name)},
     icon={Icon(when(name){RootTab.Today->Icons.Outlined.WbSunny;RootTab.Week->Icons.Outlined.CalendarMonth;else->Icons.Outlined.Restaurant},null,Modifier.size(22.dp))},
     label={Text(name.name,style=MaterialTheme.typography.labelMedium)},
     colors=NavigationBarItemDefaults.colors(selectedIconColor=EurosColors.Blue,selectedTextColor=EurosColors.Blue,indicatorColor=EurosColors.Soft,unselectedIconColor=EurosColors.Muted,unselectedTextColor=EurosColors.Muted))
   }
  }
 }
}

@Composable internal fun EurosHeader(actions:@Composable ()->Unit={},onSettings:()->Unit) {
 Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=0.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
    Spacer(Modifier.weight(1f))
    Row(verticalAlignment=Alignment.CenterVertically){
     actions()
     IconButton(onClick=onSettings){Icon(Icons.Outlined.Settings,"Settings",Modifier.size(20.dp))}
    }
   }

}

@Composable internal fun PageTitle(tab:RootTab,today:LocalDate,week:LocalDate,foodDay:LocalDate,mascotPose:MascotPose=MascotPose.IDLE) {
 val largeText=androidx.compose.ui.platform.LocalDensity.current.fontScale>1.3f
 @Composable fun counter(modifier:Modifier) {
  Column(modifier,horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy((-7).dp)) {
   Text("${ChronoUnit.DAYS.between(today,LocalDate.of(2027,1,13)).coerceAtLeast(0)}",fontSize=if(largeText)72.sp else 108.sp,lineHeight=if(largeText)80.sp else 114.sp,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold,letterSpacing=(-3).sp,textAlign=androidx.compose.ui.text.style.TextAlign.End)
   Text("days to Euros",fontSize=21.sp,lineHeight=30.sp,color=EurosColors.Muted,textAlign=androidx.compose.ui.text.style.TextAlign.End)
  }
 }
 if(largeText) Column(Modifier.fillMaxWidth().padding(bottom=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
  counter(Modifier.fillMaxWidth())
  Weasel(mascotPose,Modifier.size(120.dp,141.dp))
 } else Row(Modifier.fillMaxWidth().padding(bottom=12.dp),verticalAlignment=Alignment.CenterVertically) {
  counter(Modifier.weight(1f))
  Box(Modifier.weight(1f),contentAlignment=Alignment.Center) { Weasel(mascotPose,Modifier.size(120.dp,141.dp)) }
 }
}
