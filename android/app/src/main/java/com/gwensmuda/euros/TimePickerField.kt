package com.gwensmuda.euros

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

object TimeDisplay {
 fun label(value:String)=runCatching{LocalTime.parse(value).format(DateTimeFormatter.ofPattern("h:mm a",Locale.US))}.getOrDefault(value)
 fun stored(hour:Int,minute:Int)=LocalTime.of(hour,minute).toString()
}

/** Display AM/PM while retaining the existing ISO local-time storage contract. */
@Composable fun TimePickerField(value:String,onChange:(String)->Unit,modifier:Modifier=Modifier,enabled:Boolean=true) {
 val context=LocalContext.current
 OutlinedButton(enabled=enabled,modifier=modifier.fillMaxWidth(),onClick={
  val time=runCatching{LocalTime.parse(value)}.getOrDefault(LocalTime.now())
  TimePickerDialog(context,{_,hour,minute->onChange(TimeDisplay.stored(hour,minute))},time.hour,time.minute,false).show()
 }) {Text(TimeDisplay.label(value))}
}
