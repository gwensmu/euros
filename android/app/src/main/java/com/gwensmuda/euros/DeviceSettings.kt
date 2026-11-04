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
 val nutritionAvailable=remember(context){androidx.health.connect.client.HealthConnectClient.getSdkStatus(context)==androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE}
 val nutritionEnabled by repo.dao.observeMeta(NutritionExport.ENABLED).collectAsState(initial=null)
 val nutritionStatus by repo.dao.observeMeta(NutritionExport.STATUS).collectAsState(initial=null)
 val nutritionPermission=rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()){granted->scope.launch {
  if(NutritionExport.permission in granted){repo.dao.meta(Metadata(NutritionExport.ENABLED,"true"));NutritionExport.request(context)}
  else repo.dao.meta(Metadata(NutritionExport.STATUS,"Nutrition write access needed"))
 }}
 Text("Food logs",style=MaterialTheme.typography.titleMedium)
 if(!nutritionAvailable)Text("Health Connect unavailable",style=MaterialTheme.typography.bodySmall)
 Text("Share saved calories and macros with Health Connect, including edits and deletions.",style=MaterialTheme.typography.bodyMedium)
 if(nutritionEnabled=="true") {
  nutritionStatus?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
  TextButton(enabled=nutritionAvailable,onClick={nutritionPermission.launch(setOf(NutritionExport.permission))}){Text("Sync food logs")}
  TextButton(onClick={scope.launch{repo.dao.meta(Metadata(NutritionExport.ENABLED,"false"))}}){Text("Stop sharing")}
 } else {
  TextButton(enabled=nutritionAvailable,onClick={nutritionPermission.launch(setOf(NutritionExport.permission))}){Text("Share food logs")}
  Text("Includes existing saved entries. Stopping sharing leaves already shared entries in Health Connect.",style=MaterialTheme.typography.bodySmall)
  nutritionStatus?.takeIf{it.contains("access needed")}?.let{Text(it)}
 }
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
