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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

class EurosApplication:android.app.Application() {
 val repository by lazy { Repository(EurosDatabase.open(this)) }
 val connection by lazy { SyncConnection(repository,SecureEnrollmentStore(this),BackgroundSync(this),reconcile={
  ReminderScheduler(this,repository.dao).reconcile(repository.dao.recordsIncludingDeleted().filter{!it.deleted})
 }) }
 internal val applicationScope=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+Dispatchers.IO)
 val weekTracker by lazy { WeekTrackerCache(repository.dao.observeMeta(HealthImport.KEY),applicationScope,repository.dao.observe()) }
 override fun onCreate(){super.onCreate();weekTracker;applicationScope.launch {
  connection.initialize()
  NutritionExport.schedule(this@EurosApplication)
  launch { repository.dao.observeFoodIncludingDeleted().collect { NutritionExport.request(this@EurosApplication) } }
  launch { repository.dao.observe().map{rows->rows.filter{it.type=="activities"}}.distinctUntilChanged().collect {
   try { HealthImport(this@EurosApplication,repository.dao).reconcileLinks() }
   catch(e:kotlinx.coroutines.CancellationException){throw e}
   catch(_:Exception){repository.dao.meta(Metadata("health_refresh_error","Couldn't link tracker sessions. Refresh Health Connect."))}
  } }
  WeeklyReviewWork.resume(this@EurosApplication,repository)
  HealthBackground.schedule(this@EurosApplication)
  val trigger=PendingSyncTrigger()
  repository.dao.observePending().collect { if(trigger.changed(it))connection.request() }
 }}
}
internal fun consumeLaunchDestination(intent:android.content.Intent):String? {
 val destination=intent.getStringExtra("activity_id")
 intent.removeExtra("activity_id")
 if(destination!=null) { intent.data=null;return destination }
 return if(intent.action==android.content.Intent.ACTION_MAIN && intent.hasCategory(android.content.Intent.CATEGORY_LAUNCHER)) "@today" else null
}
class MainActivity:ComponentActivity() {
 private var displayZoneId=PlanDomain.zone.id
 private val timezoneReceiver=object:android.content.BroadcastReceiver() {
  override fun onReceive(context:android.content.Context,intent:android.content.Intent) {
   if(displayZoneId!=PlanDomain.zone.id){displayZoneId=PlanDomain.zone.id;recreate()}
  }
 }
 override fun onDestroy(){unregisterReceiver(timezoneReceiver);super.onDestroy()}

 private var notificationId by mutableStateOf<String?>(null)
 override fun onStart(){super.onStart();if(displayZoneId!=PlanDomain.zone.id){displayZoneId=PlanDomain.zone.id;recreate();return};(application as EurosApplication).let { app ->
  lifecycleScope.launch(Dispatchers.IO){app.connection.initialize();app.connection.request();HealthBackground.refresh(app);NutritionExport.request(app)}
 }}
 override fun onNewIntent(intent:android.content.Intent){super.onNewIntent(intent);setIntent(intent);notificationId=consumeLaunchDestination(intent)}
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  androidx.core.content.ContextCompat.registerReceiver(this,timezoneReceiver,android.content.IntentFilter(android.content.Intent.ACTION_TIMEZONE_CHANGED),androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
  val launchDestination=consumeLaunchDestination(intent)
  notificationId=if(savedInstanceState==null)launchDestination else null
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
 var strengthFeedbackId by rememberSaveable{mutableStateOf<String?>(null)}
 var importedId by rememberSaveable{mutableStateOf<String?>(null)}
 var weighing by rememberSaveable{mutableStateOf(false)}
 var review by rememberSaveable { mutableStateOf(false) }
 var foodEditor by remember { mutableStateOf(false) }
 var openFoodEntry by remember { mutableStateOf(false) }
 var foodActivity by remember { mutableStateOf<String?>(null) }
 var error by remember { mutableStateOf<String?>(null) }
 var snoozeId by remember{mutableStateOf<String?>(null)}
 val reminderScope=rememberCoroutineScope()
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
 val localReminderInputs by repo.dao.observeMeta(HealthImport.KEY).collectAsState(initial=null)
 val dismissedReminderMeals by repo.dao.observeMeta("dismissed_meals:$today").collectAsState(initial=null)
 LaunchedEffect(records,localReminderInputs,dismissedReminderMeals,today){try{withContext(Dispatchers.IO){reminders.reconcile(records)}}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){error="Couldn't update reminders"}}
 LaunchedEffect(notificationId){if(notificationId!=null){selected=null;strengthFeedbackId=null;settings=false;review=false;weighing=false;importedId=null;openFoodEntry=false;foodEditor=false;snoozeId=null;when(notificationId){"@weight"->{weighing=true;tab=RootTab.Today};"@today"->tab=RootTab.Today;"@food-overview"->{tab=RootTab.Food;foodDate=today.toString();openFoodEntry=false};"@food"->{tab=RootTab.Food;foodDate=today.toString();openFoodEntry=true};"@review"->{tab=RootTab.Week;week=today.with(java.time.DayOfWeek.MONDAY).toString();review=true};else->if(notificationId.startsWith("@snooze:"))snoozeId=notificationId.removePrefix("@snooze:") else selected=notificationId};onNotificationHandled()}}
 snoozeId?.let{id->SnoozeDialog(onClose={snoozeId=null},onSave={until->withContext(Dispatchers.IO){reminders.snoozeUntil(id,until)};snoozeId=null})}

 val detail=records.find { it.type=="activities" && it.id==selected }
 fun open(record:Record){selected=record.id}
 fun food(activity:String?=null){foodDate=today.toString();foodActivity=activity;selected=null;tab=RootTab.Food;openFoodEntry=true}
 fun closeOverlay() {
  selected=null
  settings=false
  review=false
  importedId=null
  weighing=false
 }
 val hasOverlay=strengthFeedbackId!=null || selected!=null || settings || review || importedId!=null || weighing
 BackHandler(hasOverlay,::closeOverlay)
 val focused=hasOverlay || foodEditor
 var completionSequence by remember{mutableLongStateOf(0L)}
 var completionReceivedAt by remember{mutableLongStateOf(0L)}
 var shownCompletion by remember{mutableLongStateOf(0L)}
 var reactionPose by remember{mutableStateOf<MascotPose?>(null)}
 var latestReaction by remember{mutableStateOf(MascotPose.PLEASED)}
 LaunchedEffect(repo){repo.localCompletions.collect { pose ->
  latestReaction=pose
  completionSequence++;completionReceivedAt=android.os.SystemClock.elapsedRealtime()
 }}
 val homeVisible=tab==RootTab.Today && !focused
 LaunchedEffect(homeVisible,completionSequence) {
  if(homeVisible && completionSequence>shownCompletion) {
   shownCompletion=completionSequence
   if(android.os.SystemClock.elapsedRealtime()-completionReceivedAt<60_000L) {
    reactionPose=latestReaction
    try{kotlinx.coroutines.delay(6000)}finally{reactionPose=null}
   }
  }
 }
 val mascotState=MascotRules.evaluate(records,java.time.Instant.now(),nextItem=PlanDomain.next(records,today),eventDate=LocalDate.parse(authoredPlan.json().getJSONObject("event").getString("date")))
 val mascotPose=reactionPose ?: mascotState.pose
 val pageScroll=rememberScrollState()
 LaunchedEffect(tab,selected,settings,review,importedId,foodEditor,strengthFeedbackId){pageScroll.scrollTo(0)}
 val snackbar=remember{SnackbarHostState()}
 val actionScope=rememberCoroutineScope()
 fun showCompletionUndo(record:Record) { actionScope.launch {
  try {
   val completed=repo.dao.record(record.type,record.id)
   if(snackbar.showSnackbar("${org.json.JSONObject(record.payload).optString("title")} done","Undo",duration=SnackbarDuration.Short)==SnackbarResult.ActionPerformed && completed!=null) {
    val restored=org.json.JSONObject(completed.payload).put("status","pending");restored.remove("completed_at");restored.remove("strength_feedback")
    repo.upsert(record.type,record.id,restored,expectedPayload=completed.payload)
   }
  } catch(e:Exception){snackbar.showSnackbar(e.message ?: "Couldn't update activity")}
 } }
 fun closeStrengthFeedback() {
  val saved=records.find{it.type=="activities" && it.id==strengthFeedbackId}
  strengthFeedbackId=null;selected=null
  if(saved!=null)showCompletionUndo(saved)
 }
 CompositionLocalProvider(LocalExerciseDone provides { record -> actionScope.launch {
  try {
   repo.complete(record,"done")
   if(org.json.JSONObject(record.payload).optString("kind")=="weights")strengthFeedbackId=record.id
   else showCompletionUndo(record)
  } catch(e:Exception){snackbar.showSnackbar(e.message ?: "Couldn't update activity")}
 } }) {
 Scaffold(snackbarHost={SnackbarHost(snackbar)},containerColor=Color.White,bottomBar={if(!focused) EurosNavigation(tab){tab=it}}) { padding ->
  Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
   if(!focused) EurosHeader(actions={if(tab==RootTab.Today)HomeHealthSync(repo,snackbar)}) {settings=true}
   Column(Modifier.fillMaxSize().verticalScroll(pageScroll).padding(horizontal=24.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
   when {
    strengthFeedbackId!=null -> {
     val feedbackRecord=records.find{it.type=="activities" && it.id==strengthFeedbackId}
     if(feedbackRecord!=null && PlanDomain.status(feedbackRecord)=="done")StrengthFeedbackScreen(feedbackRecord,repo,::closeStrengthFeedback)
     else {Text("This activity is no longer available");TextButton(onClick={strengthFeedbackId=null}){Text("Close")}}
    }
    weighing -> {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Weight",style=MaterialTheme.typography.headlineMedium);IconButton(onClick={weighing=false}){Icon(Icons.Outlined.Close,"Close")}};WeightPanel(repo,records,today,onSaved={weighing=false})}
    settings -> SettingsScreen(repo,pending,records,onClose={settings=false})
    importedId!=null -> ImportedWorkoutScreen(importedId!!,repo){importedId=null}
    review -> WeeklyReview(repo,records,LocalDate.parse(week)){review=false}
    detail!=null -> ActivityDetail(detail,records,repo,{selected=null},::open,{food(detail.id)})
    selected!=null -> {Text("This activity is no longer available");TextButton(onClick={selected=null}){Text("Close")}}
    else -> {
     if(!foodEditor && tab==RootTab.Today) PageTitle(tab,today,LocalDate.parse(week),foodDay,mascotPose)
     when(tab) {
      RootTab.Today -> {SundayWrapUp(today){week=today.with(java.time.DayOfWeek.MONDAY).toString();review=true};TodayContent(records,authoredPlan.day(today,records),::open,{foodDate=today.toString();foodActivity=null;openFoodEntry=false;tab=RootTab.Food},repo,onMealLog={food()});MovementCard(repo,today)}
      RootTab.Week -> {
       Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){IconButton(onClick={week=LocalDate.parse(week).minusWeeks(1).toString()}){Icon(Icons.Outlined.ChevronLeft,"Previous week")};TextButton(modifier=Modifier.weight(1f).semantics{contentDescription="Open wrap-up for ${weekTitle(LocalDate.parse(week))}"},onClick={review=true}){Icon(Icons.Outlined.Insights,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text(weekTitle(LocalDate.parse(week)),style=MaterialTheme.typography.titleMedium)};IconButton(onClick={week=LocalDate.parse(week).plusWeeks(1).toString()}){Icon(Icons.Outlined.ChevronRight,"Next week")}}
       WeekContent(records,authoredPlan,LocalDate.parse(week),::open,repo,{importedId=it})
      }
      RootTab.Food -> {
       if(!foodEditor)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){IconButton(onClick={foodDate=foodDay.minusDays(1).toString()}){Icon(Icons.Outlined.ChevronLeft,"Previous day")};TextButton(modifier=Modifier.weight(1f).semantics{contentDescription="${foodDay.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMMM d"))}. Go to today"},onClick={foodDate=today.toString()}){Text(foodDay.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d")),style=MaterialTheme.typography.titleMedium)};IconButton(onClick={foodDate=foodDay.plusDays(1).toString()}){Icon(Icons.Outlined.ChevronRight,"Next day")}}
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
}
