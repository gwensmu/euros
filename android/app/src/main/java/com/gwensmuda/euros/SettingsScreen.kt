package com.gwensmuda.euros

import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.time.LocalTime

@Composable fun SettingsScreen(repo:Repository,pending:List<Pending>,records:List<Record>,onClose:()->Unit) {
 var section by rememberSaveable { mutableStateOf("Settings") }
 var diagnostics by rememberSaveable { mutableStateOf(false) }
 val context=LocalContext.current
 val plan=remember { AuthoredPlan(context).json() }
 val connection=(context.applicationContext as EurosApplication).connection
 val syncState by connection.state.collectAsState()
 var reminderSummary by remember { mutableStateOf("Off") }
 var healthSummary by remember { mutableStateOf("No imported records") }
 LaunchedEffect(section) {
  val reminders=ReminderScheduler(context,repo.dao)
  val settings=reminders.settings()
  reminderSummary=if(settings.enabled && reminders.permissionGranted()) {
   if(settings.quietStart!=null && settings.quietEnd!=null) "On · quiet ${settings.quietStart}–${settings.quietEnd}" else "On"
  } else "Off"
  healthSummary=runCatching {
   repo.dao.meta(HealthImport.KEY)?.let { raw ->
    val timestamp=HealthSnapshotStorage.decode(raw).optString("refreshed_at")
    if(timestamp.isBlank()) "No imported records" else "Updated "+java.time.Instant.parse(timestamp).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MMM d · h:mm a"))
   } ?: "No imported records"
  }.getOrDefault("Check connection")
 }
 BackHandler(enabled=section!="Settings") { section="Settings" }
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
  Text(section,style=MaterialTheme.typography.headlineMedium)
  IconButton(onClick={if(section=="Settings")onClose() else section="Settings"}) { Icon(Icons.Outlined.Close,if(section=="Settings")"Close settings" else "Back to settings") }
 }
 if(section=="Settings") {
  Column {
   SettingsRow("Sync",if(syncState.syncing) "Syncing…" else if(syncState.error!=null) "Connection needs attention" else if(syncState.connected) "Connected · ${pending.size} pending" else "Not connected"){section="Sync"}
   SettingsRow("Health Connect",healthSummary){section="Health Connect"}
   SettingsRow("Notifications",reminderSummary){section="Notifications"}
   val goal=plan.getJSONObject("goal")
   SettingsRow("Weight goal",if(goal.isNull("selectedGoalLb"))"Not set" else "${goal.get("selectedGoalLb")} lb"){section="Weight goal"}
   val target=NutritionPolicy.targets(plan,records,java.time.LocalDate.now(PlanDomain.zone)) ?: org.json.JSONObject()
   SettingsRow("Nutrition targets",if(target.isNull("kcal"))"Not set" else "Daily · ${target.get("kcal")} kcal"){section="Nutrition targets"}
   SettingsRow("Data","Export and restore"){section="Data"}
  }
  TextButton(onClick={diagnostics=!diagnostics}){Text(if(diagnostics)"Hide diagnostics" else "Diagnostics")}
  if(diagnostics) ConnectionDiagnostics(repo,pending)
 } else if(section=="Sync") ConnectionDiagnostics(repo,pending) else SettingsExtras(repo,records,section)
}

@Composable private fun SettingsRow(title:String,value:String,onClick:()->Unit) {
 Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=18.dp),verticalAlignment=Alignment.CenterVertically) {
  Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
   Text(title,style=MaterialTheme.typography.titleMedium)
   Text(value,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }
  Icon(Icons.Outlined.ChevronRight,contentDescription=null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
 }
 HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
}

@Composable internal fun SettingsTimeRow(label:String,value:String,onChange:(String)->Unit) {
 val context=LocalContext.current
 SettingsRow(label,TimeDisplay.label(value).ifBlank{"Off"}) {
  val time=runCatching{LocalTime.parse(value)}.getOrDefault(LocalTime.now())
  TimePickerDialog(context,{_,hour,minute->onChange(LocalTime.of(hour,minute).toString())},time.hour,time.minute,false).show()
 }
}

@Composable private fun ConnectionDiagnostics(repo:Repository,pending:List<Pending>) {
 var server by rememberSaveable { mutableStateOf("") }
 var token by remember { mutableStateOf("") }
 var message by remember { mutableStateOf<String?>(null) }
 var busy by remember { mutableStateOf(false) }
 var connection by remember { mutableStateOf(false) }
 val scope=rememberCoroutineScope()
 val context=LocalContext.current
 val controller=(context.applicationContext as EurosApplication).connection
 val state by controller.state.collectAsState()
 LaunchedEffect(Unit){controller.initialize();server=state.server.ifBlank{repo.dao.meta("server") ?: ""}}
 Text(if(state.syncing) "Syncing…" else if(state.connected) "Connected" else "Not connected")
 state.lastSuccess?.let { time -> Text("Last synced "+java.time.Instant.parse(time).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MMM d · h:mm a"))) }
 state.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
 if(state.connected) TextButton(enabled=!state.syncing && !busy,onClick={scope.launch{withContext(Dispatchers.IO){controller.sync()}}}){Text("Sync now")}
 Text("${pending.size} pending changes",style=MaterialTheme.typography.bodyMedium)
 pending.filter{it.error!=null}.forEach { p ->
  Text(p.error!!,color=MaterialTheme.colorScheme.error)
  var confirm by remember(p.commandId){mutableStateOf(false)}
  TextButton(onClick={confirm=true}){Text("Use server version")}
  if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("Discard local change?")},text={Text("This record will use the server version.")},confirmButton={TextButton(onClick={scope.launch{runCatching{repo.acceptServer(p)}.onFailure{message=it.message};confirm=false}}){Text("Discard")}},dismissButton={TextButton(onClick={confirm=false}){Text("Cancel")}})
 }
 TextButton(onClick={connection=!connection}){Text(if(state.connected) "Update connection" else "Connect to server")}
 if(connection) {
  OutlinedTextField(server,{server=it},label={Text("Server URL")},singleLine=true,modifier=Modifier.fillMaxWidth())
  OutlinedTextField(token,{token=it},label={Text("Access token")},singleLine=true,visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
  Button(enabled=!busy && !state.syncing && server.isNotBlank() && token.isNotBlank(),onClick={scope.launch{
   busy=true
   try { withContext(Dispatchers.IO){controller.connect(server,token)};token="";connection=false;message="Connected" }
   catch(e:kotlinx.coroutines.CancellationException){throw e}
   catch(e:Exception){message=syncFailureMessage(e)}
   finally{busy=false}
  }}){Text(if(busy)"Syncing…" else "Sync")}
 }
 if(state.connected || state.error!=null) TextButton(enabled=!busy && !state.syncing,onClick={scope.launch{
  busy=true
  try{withContext(Dispatchers.IO){controller.disconnect()};token="";message="Disconnected"}
  catch(e:kotlinx.coroutines.CancellationException){throw e}
  catch(_:Exception){message="Couldn't disconnect. Try again"}
  finally{busy=false}
 }}){Text("Disconnect")}
 TextButton(enabled=!busy,onClick={scope.launch{
  busy=true
  try { NanoDraftInterpreter().use{message=when(it.availability().state){"available"->"On-device model available";"downloadable"->"Model download needed";"downloading"->"Model downloading";else->"On-device model unavailable"}} }
  catch(e:kotlinx.coroutines.CancellationException){throw e}
  catch(_:Exception){message="Couldn't check model"}
  finally{busy=false}
 }}){Text("Check on-device model")}
 message?.let{Text(it)}
}
