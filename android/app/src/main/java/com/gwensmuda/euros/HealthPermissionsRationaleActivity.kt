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
   Text("The diagnostic shows source counts and heart-rate coverage. When you choose Import, Euros stores exercise, heart-rate and weight records privately on this phone for session recaps and weight selection. Imported records are not uploaded. You can remove local imports in Settings; health export requires a separate selection. It does not read sleep, routes or location, and does not write to Health Connect.")
   Text("Access is used while the app is open. You can revoke it in Health Connect settings at any time.")
   Button(onClick={finish()}) { Text("Close") }
  } } }
 }
}
