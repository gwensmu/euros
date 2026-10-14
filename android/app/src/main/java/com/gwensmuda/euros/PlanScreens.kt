package com.gwensmuda.euros

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

private val Accent=Color(0xFF2563EB)
fun activityTime(record:Record)=OffsetDateTime.parse(PlanDomain.payload(record).getString("scheduled_at")).atZoneSameInstant(PlanDomain.zone).format(DateTimeFormatter.ofPattern("h:mm a"))
object PlanDisplay {
 fun template(record:Record):JSONObject {
  val p=PlanDomain.payload(record)
  return p.optJSONObject("plan_snapshot")?.optJSONObject("template") ?: p.optJSONObject("prescription") ?: JSONObject()
 }
 fun detail(record:Record):String? {
  val t=template(record)
  val instruction=t.optString("instructions").takeIf{it.isNotBlank() && it!="null"}
  if(instruction!=null) return instruction
  val minutes=t.opt("durationMinutes")?.takeIf{it!=JSONObject.NULL}
  return minutes?.let{"$it min"}
 }
 fun exercise(ex:JSONObject):String=buildList {
  fun text(key:String)=ex.opt(key)?.takeIf{it!=JSONObject.NULL}?.toString()?.takeIf{it.isNotBlank()}
  text("prescription")?.let{add(it)}
  val sets=text("sets");val reps=text("reps");val hold=text("holdSeconds")
  if(sets!=null && reps!=null)add("$sets × $reps") else {sets?.let{add("$it sets")};reps?.let{add("$it reps")}}
  hold?.let{add("$it sec")};text("loadLb")?.let{add("$it lb")}
  text("load")?.let{add(it)};text("duration")?.let{add(it)}
 }.joinToString(" · ")
 fun daySummary(records:List<Record>):String=records.joinToString(" · "){PlanDomain.payload(it).optString("title")}.ifBlank{"No activities"}
 fun initialExpanded(week:LocalDate,today:LocalDate):String=(if(today>=week && today<week.plusWeeks(1))today else week).toString()
 fun trackerName(source:String)=when{source.contains("polar",true)->"Polar workout";source.contains("fitbit",true)->"Fitbit workout";else->"Tracker workout"}
}
@Composable private fun ActivityGlyph(kind:String) {
 Surface(color=Color(0xFFEDF3FF),shape=RoundedCornerShape(9.dp),modifier=Modifier.size(34.dp)) {
  Box(contentAlignment=Alignment.Center){Icon(when(kind){"weights"->Icons.Outlined.FitnessCenter;"rest","recovery"->Icons.Outlined.Spa;"weight"->Icons.Outlined.MonitorWeight;"food"->Icons.Outlined.Restaurant;else->Icons.Outlined.SportsMartialArts},null,tint=MaterialTheme.colorScheme.onSurface,modifier=Modifier.size(19.dp))}
 }
}
@Composable fun PlanRow(record:Record,records:List<Record>,onOpen:(Record)->Unit) {
 val p=PlanDomain.payload(record);val status=PlanDomain.progress(record,records)
 Column(Modifier.fillMaxWidth()) {
  Row(Modifier.fillMaxWidth().clickable{onOpen(record)}.padding(vertical=13.dp),verticalAlignment=Alignment.CenterVertically) {
   ActivityGlyph(p.getString("kind"));Spacer(Modifier.width(12.dp))
   Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
    Text(p.getString("title"),style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
    Text(activityTime(record),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    PlanDisplay.detail(record)?.takeUnless{p.optString("kind")=="rest" && PlanDomain.children(record,records).isNotEmpty()}?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
   }
   Spacer(Modifier.width(8.dp))
   Surface(onClick={onOpen(record)},color=Color(0xFFEDF3FF),shape=RoundedCornerShape(8.dp),border=androidx.compose.foundation.BorderStroke(1.dp,EurosColors.Line),modifier=Modifier.size(48.dp)){
    Box(contentAlignment=Alignment.Center){
    Icon(when(status){"done"->Icons.Filled.CheckCircle;"skipped"->Icons.Outlined.RemoveCircleOutline;"partial"->Icons.Outlined.Timelapse;else->Icons.Outlined.Edit},"${if(status=="pending") "Log" else status} ${p.getString("title")}",tint=if(status=="skipped") Color.Gray else Accent,modifier=Modifier.size(20.dp))
    }
   }
  }
  HorizontalDivider(color=Color(0xFFDCE3EF))
 }
}
@Composable fun TodayContent(records:List<Record>,dayPlan:DayPlan,onOpen:(Record)->Unit,onFood:()->Unit) {
 val items=dayPlan.activities.map{it.record};val next=dayPlan.next?.record
 FoodSummaryBar(records,dayPlan.date,dayPlan.nutrition,onFood)
 if(next!=null) {
  val morning=OffsetDateTime.parse(PlanDomain.payload(next).getString("scheduled_at")).atZoneSameInstant(PlanDomain.zone).hour<12
  Surface(color=Color(0xFFEDF3FF),shape=RoundedCornerShape(8.dp),modifier=Modifier.fillMaxWidth()) {
   Column(Modifier.padding(horizontal=18.dp,vertical=14.dp)) {
    Text(if(morning) "This morning" else "Next up",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
    val children=PlanDomain.children(next,records).filter{PlanDomain.status(it)=="pending"}
    if(children.isNotEmpty()) children.forEach{PlanRow(it,records,onOpen)} else {
     PlanRow(next,records,onOpen)
     PlanDisplay.template(next).optJSONArray("exercises")?.let{e->for(i in 0 until e.length()) {val ex=e.getJSONObject(i);Text(ex.getString("name")+PlanDisplay.exercise(ex).takeIf{it.isNotBlank()}?.let{" · $it"}.orEmpty(),style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=7.dp))}}
    }
   }
  }
 }
 val later=items.filter{it.id!=next?.id && !PlanDomain.resolved(it,records)}
 if(later.isNotEmpty()) {
  Text("Later today",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
  Column {later.forEach{PlanRow(it,records,onOpen)}}
 } else if(items.isEmpty()) Text("No activities scheduled",style=MaterialTheme.typography.bodyMedium,color=Color.Gray)
}
@Composable fun WeekContent(records:List<Record>,authoredPlan:AuthoredPlan,week:LocalDate,onOpen:(Record)->Unit,onWrapUp:()->Unit,repo:Repository,onImported:(String)->Unit) {
 val context=LocalContext.current;val health=remember{HealthImport(context,repo.dao)}
 var imported by remember(week){mutableStateOf<List<ImportedSession>>(emptyList())}
 var expanded by rememberSaveable(week.toString()){mutableStateOf<String?>(PlanDisplay.initialExpanded(week,LocalDate.now(PlanDomain.zone)))}
 LaunchedEffect(week){val linked=health.linkedActivities().values.toSet();imported=health.sessions(week,week.plusDays(6)).filter{it.sourceKey !in linked}}
 Column {
  for(offset in 0L..6L) {
   val date=week.plusDays(offset);val open=expanded==date.toString()
   val activities=authoredPlan.day(date,records).activities.map{it.record}
   val sessions=imported.filter{it.start.atZone(PlanDomain.zone).toLocalDate()==date}
   Row(Modifier.fillMaxWidth().semantics{stateDescription=if(open)"Expanded" else "Collapsed"}.clickable{expanded=if(open)null else date.toString()}.padding(vertical=17.dp),verticalAlignment=Alignment.CenterVertically){
    Text(date.dayOfMonth.toString(),Modifier.width(40.dp),style=MaterialTheme.typography.headlineSmall,color=if(date==LocalDate.now(PlanDomain.zone))Accent else MaterialTheme.colorScheme.onSurfaceVariant)
    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){
     Text(date.format(DateTimeFormatter.ofPattern("EEEE")),style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
     Text(if(activities.isEmpty() && sessions.isNotEmpty())"${sessions.size} tracker workout${if(sessions.size==1)"" else "s"}" else PlanDisplay.daySummary(activities),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Icon(if(open)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,if(open)"Collapse ${date.dayOfWeek}" else "Expand ${date.dayOfWeek}",modifier=Modifier.size(20.dp))
   }
   if(open) Column(Modifier.padding(start=12.dp,bottom=10.dp)) {
    activities.forEach{activity->PlanRow(activity,records,onOpen);PlanDomain.children(activity,records).forEach{step->PlanRow(step,records,onOpen)}}
    sessions.forEach{session->Row(Modifier.fillMaxWidth().clickable{onImported(session.sourceKey)}.padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically){
     ActivityGlyph("bjj");Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(PlanDisplay.trackerName(session.sourcePackage),style=MaterialTheme.typography.titleSmall);Text("${session.start.atZone(PlanDomain.zone).format(DateTimeFormatter.ofPattern("h:mm a"))} · ${Duration.between(session.start,session.end).toMinutes()} min",style=MaterialTheme.typography.bodySmall)};Icon(Icons.Filled.CheckCircle,"Imported workout",tint=Accent)
    }}
   }
   HorizontalDivider(color=Color(0xFFDCE3EF))
  }
 }
 OutlinedButton(onClick=onWrapUp,shape=RoundedCornerShape(10.dp)){Icon(Icons.Outlined.Insights,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Weekly wrap-up")}
}
@Composable fun MoveDialog(record:Record,repo:Repository,onClose:()->Unit) {
 val current=OffsetDateTime.parse(PlanDomain.payload(record).getString("scheduled_at")).atZoneSameInstant(PlanDomain.zone)
 var date by rememberSaveable{mutableStateOf(current.toLocalDate().toString())}
 var time by rememberSaveable{mutableStateOf(current.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")))}
 var error by remember{mutableStateOf<String?>(null)};var busy by remember{mutableStateOf(false)};val scope=rememberCoroutineScope()
 AlertDialog(onDismissRequest=onClose,title={Text("Move session")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
  OutlinedTextField(date,{date=it},label={Text("Date · YYYY-MM-DD")},singleLine=true)
  OutlinedTextField(time,{time=it},label={Text("Time · HH:mm")},singleLine=true)
  error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
 }},confirmButton={TextButton(enabled=!busy,onClick={scope.launch{busy=true;try{repo.move(record,PlanDomain.moveTimestamp(date,time));onClose()}catch(e:Exception){error=e.message}finally{busy=false}}}){Text("Move")}},dismissButton={TextButton(onClick=onClose){Text("Cancel")}})
}
@Composable fun ActivityDetail(record:Record,records:List<Record>,repo:Repository,onClose:()->Unit,onOpen:(Record)->Unit,onFood:()->Unit) {
 val p=PlanDomain.payload(record);val item=DayPlanComposer.item(record,records);val kind=item.kind.wireName;val children=item.steps;val logging=item.logging
 var move by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)};var busy by remember{mutableStateOf(false)}
 var amount by rememberSaveable(record.id){mutableStateOf(p.optJSONObject("recovery_intake")?.optString("amount") ?: "")}
 var product by rememberSaveable(record.id){mutableStateOf(p.optJSONObject("recovery_intake")?.optString("product") ?: "")}
 val scope=rememberCoroutineScope()
 fun finish(status:String){scope.launch{busy=true;try {
  if(status=="done" && logging==LoggingMode.INTAKE) {
   val type=p.getString("recovery_step_type")
   val intake=JSONObject().put("kind",type).put("amount",amount).put("unit",if(type=="hydration") "ml" else "mg")
   if(type=="supplement")intake.put("product",product)
   repo.upsert("activities",record.id,JSONObject(record.payload).put("status",status).put("recovery_intake",intake).put("completed_at",OffsetDateTime.now(PlanDomain.zone).toString()),expectedRevision=record.revision,expectedPayload=record.payload)
  } else repo.complete(record,status)
  onClose()
 }catch(e:Exception){error=e.message}finally{busy=false}}}
 Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
  if(logging !in setOf(LoggingMode.STEPS,LoggingMode.WEIGHT,LoggingMode.UNSUPPORTED)) {
   Button(enabled=!busy,onClick={if(logging==LoggingMode.FOOD)onFood() else finish("done")}){Text(if(kind in setOf("weights","rest"))"Done" else "Log")}
   Spacer(Modifier.width(8.dp));OutlinedButton(enabled=!busy,onClick={finish("skipped")}){Text(if(kind=="weights")"Skipped" else "Skip")}
  }
  Spacer(Modifier.weight(1f));IconButton(onClick=onClose){Icon(Icons.Outlined.Close,"Close")}
 }
 Text(p.getString("title"),style=MaterialTheme.typography.headlineMedium)
 Row(verticalAlignment=Alignment.CenterVertically){Text(activityTime(record));TextButton(onClick={move=true}){Text("Move")}}
 if(move) MoveDialog(record,repo){move=false}
 val reminderContext=LocalContext.current
 var reminderEnabled by remember{mutableStateOf(false)}
 LaunchedEffect(record.id){reminderEnabled=ReminderScheduler(reminderContext,repo.dao).settings().enabled}
 if(reminderEnabled && PlanDomain.status(record)=="pending") Row {listOf(10,30,60).forEach{minutes->TextButton(onClick={scope.launch{try{ReminderScheduler(reminderContext,repo.dao).snooze(record.id,minutes)}catch(e:Exception){error=e.message}}}){Text("Snooze ${minutes}m")}}}
 error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
 if(children.isNotEmpty()) children.forEach{PlanRow(it,records,onOpen)}
 else if(logging==LoggingMode.WEIGHT) WeightPanel(repo,records,PlanDomain.day(record),record,onClose)
 else {
  val snapshot=p.optJSONObject("plan_snapshot");val template=snapshot?.optJSONObject("template") ?: snapshot
  template?.optString("instructions")?.takeIf{it.isNotBlank()}?.let{Text(it)}
  template?.optJSONArray("exercises")?.let{e->for(i in 0 until e.length()){
   val ex=e.getJSONObject(i)
   Column(Modifier.fillMaxWidth().padding(vertical=10.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
    Text(ex.getString("name"),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
    PlanDisplay.exercise(ex).takeIf{it.isNotBlank()}?.let{Text(it,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
   };HorizontalDivider(color=Color(0xFFDCE3EF))
  }}
  if(logging==LoggingMode.INTAKE) {
   if(p.optString("recovery_step_type")=="supplement")OutlinedTextField(product,{product=it},label={Text("Product")})
   OutlinedTextField(amount,{amount=it},label={Text(if(p.optString("recovery_step_type")=="hydration")"Water · ml" else "Elemental dose · mg")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
  }
  if(logging==LoggingMode.SESSION) TrackerRecap(record,repo)
 }
}
@Composable fun TrackerRecap(record:Record,repo:Repository) {
 val context=LocalContext.current;val service=remember{HealthImport(context,repo.dao)};val scope=rememberCoroutineScope()
 var recap by remember(record.id){mutableStateOf<SessionRecap?>(null)};var candidates by remember(record.id){mutableStateOf<List<ImportedSession>>(emptyList())};var choosing by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)}
 LaunchedEffect(record.id){recap=service.recap(record.id)}
 recap?.let{r->
  if(r.session.deleted)Text("Linked workout removed from Health Connect",color=Color.Gray)
  else {
   SessionGraphic(r)
  }
  TextButton(onClick={scope.launch{service.unlink(record.id);recap=null}}){Text("Unlink workout")}
 } ?: Text("No linked workout",color=Color.Gray)
 TextButton(onClick={scope.launch{try{candidates=service.candidates(record);choosing=true}catch(e:Exception){error=e.message}}}){Text("Choose workout")}
 if(choosing){if(candidates.isEmpty())Text("No workouts found. Refresh Health Connect in Settings.") else candidates.forEach{candidate->OutlinedButton(onClick={scope.launch{try{service.link(record.id,candidate.sourceKey);recap=service.recap(record.id);choosing=false}catch(e:Exception){error=e.message}}}){Text("${candidate.start.atZone(PlanDomain.zone).format(DateTimeFormatter.ofPattern("EEE h:mm a"))} · ${Duration.between(candidate.start,candidate.end).toMinutes()} min · ${if(candidate.sourcePackage.contains("polar"))"Polar" else if(candidate.sourcePackage=="com.fitbit.FitbitMobile")"Fitbit" else candidate.sourcePackage}")}}}
 error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
}

@Composable fun ImportedWorkoutScreen(sourceKey:String,repo:Repository,onClose:()->Unit) {
 val context=LocalContext.current;var recap by remember(sourceKey){mutableStateOf<SessionRecap?>(null)}
 LaunchedEffect(sourceKey){recap=HealthImport(context,repo.dao).recapForKey(sourceKey)}
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(PlanDisplay.trackerName(recap?.session?.sourcePackage.orEmpty()),style=MaterialTheme.typography.headlineMedium);IconButton(onClick=onClose){Icon(Icons.Outlined.Close,"Close")}}
 recap?.let{r->
  Text(r.session.start.atZone(PlanDomain.zone).format(DateTimeFormatter.ofPattern("EEEE, MMM d · h:mm a")))
  if(r.session.deleted)Text("Workout removed from Health Connect") else {
   SessionGraphic(r)
  }
 } ?: Text("Workout unavailable")
}
@Composable private fun SessionGraphic(r:SessionRecap) {
 val clock=DateTimeFormatter.ofPattern("h:mm a")
 val interval="${r.session.start.atZone(PlanDomain.zone).format(clock)}–${r.session.end.atZone(PlanDomain.zone).format(clock)}"
 Surface(color=Color(0xFFEDF3FF),shape=RoundedCornerShape(8.dp),modifier=Modifier.fillMaxWidth()) {
  Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
   Text("${Duration.between(r.session.start,r.session.end).toMinutes()} min",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
   Text(interval,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(40.dp)){
    Column{Text(r.averageBpm?.let{"${it.toInt()}"} ?: "—",style=MaterialTheme.typography.headlineSmall);Text("Average bpm",style=MaterialTheme.typography.bodySmall)}
    Column{Text(r.peakBpm?.toString() ?: "—",style=MaterialTheme.typography.headlineSmall);Text("Peak bpm",style=MaterialTheme.typography.bodySmall)}
   }
  }
 }
 if(r.samples.isNotEmpty()) {
  Text("Heart rate",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
  val low=(r.samples.minOf{it.bpm}/20)*20;val high=((r.samples.maxOf{it.bpm}/20)+1)*20
  Row(Modifier.fillMaxWidth().height(170.dp)) {
   Column(Modifier.width(34.dp).fillMaxHeight(),verticalArrangement=Arrangement.SpaceBetween){Text("$high",style=MaterialTheme.typography.labelSmall,color=Color.Gray);Text("${(low+high)/2}",style=MaterialTheme.typography.labelSmall,color=Color.Gray);Text("$low",style=MaterialTheme.typography.labelSmall,color=Color.Gray)}
   Canvas(Modifier.weight(1f).fillMaxHeight()) {
    val from=r.session.start.toEpochMilli();val duration=(r.session.end.toEpochMilli()-from).coerceAtLeast(1)
    val range=(high-low).coerceAtLeast(1).toFloat()
    listOf(0f,.5f,1f).forEach{y->drawLine(Color(0xFFDCE3EF),Offset(0f,size.height*y),Offset(size.width,size.height*y),1f)}
    fun point(s:HeartSample)=Offset((s.time.toEpochMilli()-from).toFloat()/duration*size.width,size.height-(s.bpm-low)/range*size.height)
    r.samples.zipWithNext().filter{Duration.between(it.first.time,it.second.time).seconds in 0..60}.forEach{(a,b)->drawLine(Accent,point(a),point(b),2.dp.toPx())}
    if(r.samples.size==1)drawCircle(Accent,3.dp.toPx(),point(r.samples.single()))
   }
  }
  Row(Modifier.fillMaxWidth().padding(start=34.dp),horizontalArrangement=Arrangement.SpaceBetween){Text(r.session.start.atZone(PlanDomain.zone).format(clock),style=MaterialTheme.typography.labelSmall,color=Color.Gray);Text(r.session.end.atZone(PlanDomain.zone).format(clock),style=MaterialTheme.typography.labelSmall,color=Color.Gray)}
 }
 Text("${r.coveredSeconds/60} min of heart-rate coverage",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable fun MovementCard(repo:Repository,date:LocalDate) {
 val context=LocalContext.current;var movement by remember(date){mutableStateOf<MovementSummary?>(null)}
 LaunchedEffect(date){movement=HealthImport(context,repo.dao).movement(date)}
 movement?.takeIf{it.walkCount>0}?.let{m->OutlinedCard(Modifier.fillMaxWidth()) {Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.DirectionsWalk,null,tint=Accent);Spacer(Modifier.width(12.dp));Column{Text("Daily movement",style=MaterialTheme.typography.titleMedium);Text("${m.walkCount} walks · ${m.workoutWalkMinutes ?: 0} min",style=MaterialTheme.typography.bodyMedium)}}}}
}
