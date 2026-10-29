package com.gwensmuda.euros

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable fun DeviceSettings() {
 val context=LocalContext.current
 val scope=rememberCoroutineScope()
 val repo=(context.applicationContext as EurosApplication).repository
 val refreshError by repo.dao.observeMeta("health_refresh_error").collectAsState(initial=null)
 refreshError?.takeIf{it.isNotBlank()}?.let{Text(it,color=MaterialTheme.colorScheme.error)}
 val probe=remember(context){HealthConnectProbe(context)}
 var report by remember { mutableStateOf<HealthProbeReport?>(null) }
 var healthMessage by remember { mutableStateOf<String?>(null) }
 fun inspect() { scope.launch {
  try { report=probe.inspect();healthMessage=null }
  catch(e:CancellationException){throw e}
  catch(_:Exception){healthMessage="Couldn't read Health Connect"}
 } }
 val permissions=rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()){inspect()}
 val background=rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()){scope.launch{HealthBackground.refresh(context,true)};inspect()}
 if(HealthBackground.available(context))TextButton(onClick={background.launch(setOf(androidx.health.connect.client.permission.HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND))}){Text("Allow background refresh")}
 Text("Polar Flow",style=MaterialTheme.typography.titleMedium)
 Text("Workouts and heart rate",style=MaterialTheme.typography.bodyMedium)
 Text("Fitbit",style=MaterialTheme.typography.titleMedium)
 Text("Daily movement and weight",style=MaterialTheme.typography.bodyMedium)
 TextButton(onClick={permissions.launch(HealthConnectProbe.permissions)}){Text("Connect trackers")}
 var diagnostics by remember { mutableStateOf(false) }
 TextButton(onClick={diagnostics=!diagnostics;if(diagnostics)inspect()}){Text(if(diagnostics) "Hide access details" else "Access details")}
 healthMessage?.let{Text(it)}
 if(diagnostics) report?.let { r ->
  Text(r.status)
  r.categories.forEach { c ->
   Text(c.name+": "+when { !c.granted->"Access needed"; c.error!=null->c.error; c.sources.isEmpty()->"No shared records"; else->c.sources.joinToString { s -> "${sourceName(s.sourcePackage)} ${s.recordCount}" } }+(if(c.truncated) " · partial" else ""))
  }
  r.coverage.forEach { c -> Text("${sourceName(c.sourcePackage)} · ${c.sessionCount} sessions · "+if(c.heartRateReadable) "${c.sessionsWithSamples} with heart rate" else "heart rate access needed") }
  if(r.available) Text("Last ${r.days} days",style=MaterialTheme.typography.bodySmall)
 }
}
private fun sourceName(name:String)=when(name){"fi.polar.polarflow"->"Polar Flow";"com.fitbit.FitbitMobile"->"Fitbit";else->if(name.startsWith("com.android.healthconnect.phone.")) "Phone" else name}
