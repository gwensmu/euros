package com.gwensmuda.euros

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

@Composable fun SettingsExtras(repo:Repository,records:List<Record>,section:String) {
 val context=LocalContext.current;val scope=rememberCoroutineScope()
 val reminders=remember{ReminderScheduler(context,repo.dao)};val health=remember{HealthImport(context,repo.dao)}
 var settings by remember{mutableStateOf(ReminderSettings())};var quietStart by remember{mutableStateOf("")};var quietEnd by remember{mutableStateOf("")}
 var archive by remember{mutableStateOf<org.json.JSONObject?>(null)}
 var status by remember{mutableStateOf<String?>(null)};var includeHealth by remember{mutableStateOf(false)};var confirmRemoval by remember{mutableStateOf(false)}
 LaunchedEffect(Unit){settings=reminders.settings();quietStart=settings.quietStart ?: "";quietEnd=settings.quietEnd ?: ""}
 val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->scope.launch{settings=settings.copy(enabled=granted);reminders.saveSettings(settings);reminders.reconcile(records);if(!granted)status="Notifications are off"}}
 val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null)scope.launch{try{ExportService(context,repo.dao).write(uri,includeHealth);status="Export saved"}catch(_:Exception){status="Couldn't save export"}}}
 val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)scope.launch{try{archive=ExportService(context,repo.dao).read(uri)}catch(e:Exception){status=e.message ?: "Couldn't open export"}}}
 archive?.let{data->AlertDialog(onDismissRequest={archive=null},title={Text("Restore backup?")},text={Text("${data.getJSONArray("records").length()} records · ${data.optJSONObject("local_state")?.length() ?: 0} local settings${if(data.has("health_imports") && data.getInt("schema_version")==2) " · tracker snapshot" else ""}. Existing data stays unchanged. New records queue for sync. Reconnect and grant permissions on a new phone; reminders stay off when restored.")},confirmButton={TextButton(onClick={scope.launch{try{val result=repo.restoreArchive(data);status="${result.imported} records and ${result.localImported} local items restored · ${result.skipped + result.localSkipped} kept unchanged";archive=null}catch(e:Exception){status=e.message;archive=null}}}){Text("Restore")}},dismissButton={TextButton(onClick={archive=null}){Text("Cancel")}})}
 if(section=="Health Connect") {
 DeviceSettings()
 TextButton(onClick={confirmRemoval=true}){Text("Remove local tracker data")}
 if(confirmRemoval)AlertDialog(onDismissRequest={confirmRemoval=false},title={Text("Remove local tracker data?")},text={Text("Manual logs and shared workout copies stay in Euros. Refresh can import tracker data on this phone again.")},confirmButton={TextButton(onClick={scope.launch{health.clearImports();confirmRemoval=false;status="Imported data removed"}}){Text("Remove")}},dismissButton={TextButton(onClick={confirmRemoval=false}){Text("Cancel")}})
 }
 if(section=="Notifications") {
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("Weasel reminders");Switch(settings.enabled,onCheckedChange={enabled->if(enabled && Build.VERSION.SDK_INT>=33 && !reminders.permissionGranted())permission.launch(Manifest.permission.POST_NOTIFICATIONS) else scope.launch{settings=settings.copy(enabled=enabled);reminders.saveSettings(settings);reminders.reconcile(records)}})}
 SettingsTimeRow("Quiet from",quietStart){quietStart=it}
 SettingsTimeRow("Quiet until",quietEnd){quietEnd=it}
 TextButton(onClick={quietStart="";quietEnd=""}){Text("Clear quiet hours")}
 TextButton(onClick={scope.launch{try{if(quietStart.isNotBlank())java.time.LocalTime.parse(quietStart);if(quietEnd.isNotBlank())java.time.LocalTime.parse(quietEnd);require(quietStart.isBlank()==quietEnd.isBlank()){ "Set both quiet-hour times" };settings=settings.copy(quietStart=quietStart.ifBlank{null},quietEnd=quietEnd.ifBlank{null});reminders.saveSettings(settings);reminders.reconcile(records);status="Reminders updated"}catch(e:Exception){status=e.message}}}){Text("Save quiet hours")}
 }
 if(section=="Weight goal" || section=="Nutrition targets") {
 val plan=remember{AuthoredPlan(context).json()}
 val goal=plan.getJSONObject("goal")
 if(section=="Weight goal") {
 Text(if(goal.isNull("selectedGoalLb")) "Not set" else "${goal.get("selectedGoalLb")} lb",style=MaterialTheme.typography.headlineLarge)
 }
 if(section=="Nutrition targets") {
  val today=java.time.LocalDate.now(PlanDomain.zone)
  val target=NutritionPolicy.targets(plan,records,today) ?: org.json.JSONObject()
  val maintenance=NutritionPolicy.maintenanceStart(plan,records)?.let{it<=today}==true
  Text(if(maintenance) "Maintenance" else "Daily",style=MaterialTheme.typography.titleMedium)
  Text("${if(target.isNull("kcal")) "—" else target.get("kcal")} kcal",style=MaterialTheme.typography.headlineMedium)
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
   listOf("proteinG" to "Protein","carbohydrateG" to "Carbs","fatG" to "Fat").forEach { (field,name) ->
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
     Text(FoodDomain.targetLabel(target,field)?.let{"$it g"} ?: "—",style=MaterialTheme.typography.titleLarge)
     Text(name,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
   }
  }
  HorizontalDivider()

 }
 }
 if(section=="Data") {
 Row(verticalAlignment=Alignment.CenterVertically){Checkbox(includeHealth,{includeHealth=it});Text("Include tracker data")}
 TextButton(onClick={export.launch("euros-export.json")}){Text("Export data")}
 TextButton(onClick={restore.launch(arrayOf("application/json"))}){Text("Restore backup")}
 }
 status?.let{Text(it,style=MaterialTheme.typography.bodyMedium)}
}
