package com.gwensmuda.euros

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.*

@OptIn(ExperimentalLayoutApi::class)
@Composable fun SnoozeDialog(onClose:()->Unit,onSave:suspend (Instant)->Unit) {
 var minutes by rememberSaveable{mutableIntStateOf(30)}
 var custom by rememberSaveable{mutableStateOf(false)}
 var chosen by rememberSaveable{mutableStateOf(LocalTime.now(PlanDomain.zone).plusMinutes(30).withSecond(0).withNano(0).toString())}
 var busy by remember{mutableStateOf(false)}
 var error by remember{mutableStateOf<String?>(null)}
 val scope=rememberCoroutineScope()
 AlertDialog(onDismissRequest={if(!busy)onClose()},icon={Icon(Icons.Outlined.Snooze,null)},title={Text("Snooze")},text={
  Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
   FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    listOf(10,30,60).forEach{value->FilterChip(selected=!custom && minutes==value,enabled=!busy,onClick={minutes=value;custom=false;error=null},label={Text("$value min")})}
   }
   FilterChip(selected=custom,enabled=!busy,onClick={custom=!custom;error=null},label={Text("Choose time")})
   if(custom)TimePickerField(chosen,{chosen=it;error=null},enabled=!busy)
   error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
  }
 },confirmButton={TextButton(enabled=!busy,onClick={
  busy=true
  scope.launch {
   try {
    val now=ZonedDateTime.now(PlanDomain.zone)
    val until=if(custom){val today=now.toLocalDate().atTime(LocalTime.parse(chosen)).atZone(PlanDomain.zone);(if(today>now)today else today.plusDays(1)).toInstant()}else now.toInstant().plusSeconds(minutes*60L)
    onSave(until)
   }catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){error=e.message ?: "Couldn't snooze. Try again."}finally{busy=false}
  }
 }){Text(if(busy)"Saving…" else "Snooze")}},dismissButton={TextButton(enabled=!busy,onClick=onClose){Text("Cancel")}})
}
