package com.gwensmuda.euros

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class HealthPermissionsRationaleActivity:ComponentActivity() {
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  setContent { MaterialTheme { Column(Modifier.padding(24.dp)) {
   Text("Health data access",style=MaterialTheme.typography.headlineMedium)
   Text("Euros checks exercise, heart rate, resting heart rate, weight, steps, distance and energy records shared through Health Connect. You can allow each category separately.")
   Text("Euros imports workouts, heart rate and observed scale readings for your activity recaps and weight chart. Selected Polar training records and observed scale readings synchronize with your Euros server. Background reading requires separate permission.")
   Text("If you enable Share food logs, Euros writes saved food entries—including existing entries, edits and deletions—to Health Connect. Calories and macros are shared only when known. Planned meals and targets are not shared. Other apps can read these records only with their own Health Connect permissions.")
   Text("You can stop food sharing in Euros or revoke permissions in Health Connect. Stopping sharing leaves already shared entries in Health Connect. Euros does not read sleep, routes or location.")
   Button(onClick={finish()}) { Text("Close") }
  } } }
 }
}
