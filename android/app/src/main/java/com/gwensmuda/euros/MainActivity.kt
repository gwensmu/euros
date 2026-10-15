package com.gwensmuda.euros

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

class EurosApplication:android.app.Application() {
 val repository by lazy { Repository(EurosDatabase.open(this)) }
 val connection by lazy { SyncConnection(repository,SecureEnrollmentStore(this),BackgroundSync(this),reconcile={
  ReminderScheduler(this,repository.dao).reconcile(repository.dao.recordsIncludingDeleted().filter{!it.deleted})
 }) }
 private val applicationScope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+Dispatchers.IO)
 val weekTracker by lazy { WeekTrackerCache(repository.dao.observeMeta(HealthImport.KEY),applicationScope) }
 override fun onCreate(){super.onCreate();weekTracker;applicationScope.launch {
  connection.initialize()
  val trigger=PendingSyncTrigger()
  repository.dao.observePending().collect { if(trigger.changed(it))connection.request() }
 }}
}
class MainActivity:ComponentActivity() {
 private var notificationId by mutableStateOf<String?>(null)
 override fun onStart(){super.onStart();(application as EurosApplication).let { app ->
  lifecycleScope.launch(Dispatchers.IO){app.connection.initialize();app.connection.request()}
 }}
 override fun onNewIntent(intent:android.content.Intent){super.onNewIntent(intent);setIntent(intent);notificationId=intent.getStringExtra("activity_id")}
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  notificationId=intent.getStringExtra("activity_id")
  window.statusBarColor=android.graphics.Color.WHITE
  window.navigationBarColor=android.graphics.Color.WHITE
  androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).isAppearanceLightStatusBars=true
  androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).isAppearanceLightNavigationBars=true
  val repository=(application as EurosApplication).repository
  setContent { EurosTheme { Euros(repository,notificationId){notificationId=null} } }
 }
}
@Composable fun Euros(repo:Repository,notificationId:String?=null,onNotificationHandled:()->Unit={}) {
 val records by repo.dao.observe().collectAsState(initial=emptyList())
 val pending by repo.dao.observePending().collectAsState(initial=emptyList())
 var tab by rememberSaveable(stateSaver=Saver<RootTab,String>(
  save={it.name},
  restore={saved->RootTab.entries.firstOrNull{it.name==saved} ?: RootTab.Today},
 )) { mutableStateOf(RootTab.Today) }
 var selected by rememberSaveable { mutableStateOf<String?>(null) }
 var settings by rememberSaveable { mutableStateOf(false) }
 var importedId by rememberSaveable{mutableStateOf<String?>(null)}
 var review by rememberSaveable { mutableStateOf(false) }
 var foodEditor by remember { mutableStateOf(false) }
 var openFoodEntry by remember { mutableStateOf(false) }
 var foodActivity by remember { mutableStateOf<String?>(null) }
 var error by remember { mutableStateOf<String?>(null) }
 val context=LocalContext.current
 val authoredPlan=remember(context){AuthoredPlan(context)}
 val reminders=remember(context){ReminderScheduler(context,repo.dao)}
 var today by remember{mutableStateOf(LocalDate.now(PlanDomain.zone))}
 var foodDate by rememberSaveable{mutableStateOf(today.toString())}
 val foodDay=LocalDate.parse(foodDate)
 var week by rememberSaveable{mutableStateOf(today.with(java.time.DayOfWeek.MONDAY).toString())}
 LaunchedEffect(Unit){while(true){today=LocalDate.now(PlanDomain.zone);kotlinx.coroutines.delay(30000)}}
 LaunchedEffect(repo) {
  try { withContext(Dispatchers.IO){repo.bootstrapAuthoredSchedule(context.applicationContext)} }
  catch(e:kotlinx.coroutines.CancellationException){throw e}
  catch(_:Exception){error="Couldn't load the plan. Reopen Euros to retry."}
 }
 LaunchedEffect(records){try{reminders.reconcile(records)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){error="Couldn't update reminders"}}
 LaunchedEffect(notificationId){if(notificationId!=null){selected=notificationId;settings=false;review=false;onNotificationHandled()}}
 val detail=records.find { it.type=="activities" && it.id==selected }
 fun open(record:Record){selected=record.id}
 fun food(activity:String?=null){foodDate=today.toString();foodActivity=activity;selected=null;tab=RootTab.Food;openFoodEntry=true}
 fun closeOverlay() {
  selected=null
  settings=false
  review=false
  importedId=null
 }
 val hasOverlay=selected!=null || settings || review || importedId!=null
 BackHandler(hasOverlay,::closeOverlay)
 val focused=hasOverlay || foodEditor
 var completionSequence by remember{mutableLongStateOf(0L)}
 var completionReceivedAt by remember{mutableLongStateOf(0L)}
 var shownCompletion by remember{mutableLongStateOf(0L)}
 var pleased by remember{mutableStateOf(false)}
 LaunchedEffect(repo){repo.localCompletions.collect {
  completionSequence++;completionReceivedAt=android.os.SystemClock.elapsedRealtime()
 }}
 val homeVisible=tab==RootTab.Today && !focused
 LaunchedEffect(homeVisible,completionSequence) {
  if(homeVisible && completionSequence>shownCompletion) {
   shownCompletion=completionSequence
   if(android.os.SystemClock.elapsedRealtime()-completionReceivedAt<60_000L) {
    pleased=true
    try{kotlinx.coroutines.delay(3000)}finally{pleased=false}
   }
  }
 }
 val mascotState=MascotRules.evaluate(records,java.time.Instant.now(),nextItem=PlanDomain.next(records,today))
 val mascotPose=if(pleased)MascotPose.PLEASED else mascotState.pose
 val pageScroll=rememberScrollState()
 LaunchedEffect(tab,selected,settings,review,importedId,foodEditor){pageScroll.scrollTo(0)}
 Scaffold(containerColor=Color.White,bottomBar={if(!focused) EurosNavigation(tab){tab=it}}) { padding ->
  Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
   if(!focused) EurosHeader {settings=true}
   Column(Modifier.fillMaxSize().verticalScroll(pageScroll).padding(horizontal=22.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
   when {
    settings -> SettingsScreen(repo,pending,records,onClose={settings=false})
    importedId!=null -> ImportedWorkoutScreen(importedId!!,repo){importedId=null}
    review -> WeeklyReview(repo,records,LocalDate.parse(week)){review=false}
    detail!=null -> ActivityDetail(detail,records,repo,{selected=null},::open,{food(detail.id)})
    selected!=null -> {Text("This activity is no longer available");TextButton(onClick={selected=null}){Text("Close")}}
    else -> {
     if(!foodEditor) PageTitle(tab,today,LocalDate.parse(week),foodDay,mascotPose)
     when(tab) {
      RootTab.Today -> {TodayContent(records,authoredPlan.day(today,records),::open,{foodDate=today.toString();foodActivity=null;openFoodEntry=false;tab=RootTab.Food});MovementCard(repo,today)}
      RootTab.Week -> {
       Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){IconButton(onClick={week=LocalDate.parse(week).minusWeeks(1).toString()}){Icon(Icons.Outlined.ChevronLeft,"Previous week")};TextButton(onClick={week=today.with(java.time.DayOfWeek.MONDAY).toString()}){Text("This week")};IconButton(onClick={week=LocalDate.parse(week).plusWeeks(1).toString()}){Icon(Icons.Outlined.ChevronRight,"Next week")}}
       WeekContent(records,authoredPlan,LocalDate.parse(week),::open,{review=true},repo,{importedId=it})
      }
      RootTab.Food -> {
       if(!foodEditor)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){IconButton(onClick={foodDate=foodDay.minusDays(1).toString()}){Icon(Icons.Outlined.ChevronLeft,"Previous day")};TextButton(onClick={foodDate=today.toString()}){Text("Today")};IconButton(onClick={foodDate=foodDay.plusDays(1).toString()}){Icon(Icons.Outlined.ChevronRight,"Next day")}}
       val dayPlan=authoredPlan.day(foodDay,records)
       FoodScreen(repo,records,foodDay,dayPlan.nutrition,dayPlan.meals,mealChoices=FoodDomain.mealReferences(authoredPlan.json(),foodDay,records),mealChoicesForDate={date->FoodDomain.mealReferences(authoredPlan.json(),date,records)},openEntry=openFoodEntry,plannedActivityId=foodActivity,onEntryOpened={openFoodEntry=false},onEditorVisibility={foodEditor=it})
      }
     }
    }
   }
   error?.let { Text(it,color=MaterialTheme.colorScheme.error);TextButton(onClick={error=null}){Text("Dismiss")} }
   }
  }
 }
}
