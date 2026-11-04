package com.gwensmuda.euros

import androidx.compose.foundation.text.selection.SelectionContainer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable fun WeightPanel(repo:Repository,records:List<Record>,date:LocalDate,activity:Record?=null,onSaved:()->Unit={},showGraph:Boolean=true) {
 var value by rememberSaveable(date.toString()){mutableStateOf("")};var id by rememberSaveable(date.toString()){mutableStateOf(UUID.randomUUID().toString())}
 var measuredDate by rememberSaveable(date.toString()){mutableStateOf(minOf(date,LocalDate.now(PlanDomain.zone)).toString())}
 var originalTime by rememberSaveable{mutableStateOf<String?>(null)}
 var originalPayload by rememberSaveable{mutableStateOf<String?>(null)}
 var baseRevision by rememberSaveable{mutableStateOf<Long?>(null)}
 var error by remember{mutableStateOf<String?>(null)};var busy by remember{mutableStateOf(false)}
 val scope=rememberCoroutineScope();val context=LocalContext.current;val importer=remember{HealthImport(context,repo.dao)}
 var imported by remember{mutableStateOf<List<ImportedWeight>>(emptyList())}
 if(showGraph) WeightGraph(records)
 OutlinedTextField(measuredDate,{measuredDate=it;originalTime=null},label={Text("Date · YYYY-MM-DD")},singleLine=true,modifier=Modifier.fillMaxWidth())
 OutlinedTextField(value,{value=it},label={Text("Weight · lb")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
 fun save(payload:JSONObject,newImported:Boolean=false){scope.launch{busy=true;try{
  require(!OffsetDateTime.parse(payload.getString("measured_at")).toInstant().isAfter(Instant.now())){"Choose an actual measurement time"}
  val origin=if(newImported)null else originalPayload
  val merged=origin?.let{JSONObject(it)} ?: JSONObject()
  if(origin!=null && merged.has("source_key")){merged.put("manual_override",true);if(!merged.has("source_weight_lb"))merged.put("source_weight_lb",merged.get("weight_lb"));if(!merged.has("source_measured_at"))merged.put("source_measured_at",merged.get("measured_at"))}
  payload.keys().forEach{key->if(key!="context" || origin==null)merged.put(key,payload.get(key))}
  merged.put("weekly_anchor",LocalDate.parse(payload.getString("local_date")).with(DayOfWeek.MONDAY).toString())
  repo.saveWeight(if(newImported)UUID.randomUUID().toString() else id,merged,expectedRevision=if(newImported)null else baseRevision,expectedPayload=origin)
  activity?.takeIf{PlanDomain.day(it).with(DayOfWeek.MONDAY).toString()==merged.getString("weekly_anchor")}?.let{repo.complete(it,"done")};value="";id=UUID.randomUUID().toString();originalPayload=null;baseRevision=null;originalTime=null;onSaved()
 }catch(e:Exception){error=e.message}finally{busy=false}}}
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){IconButton(enabled=!busy && value.isNotBlank(),onClick={try{save(PlanDomain.weightPayload(value,originalTime?.let{OffsetDateTime.parse(it)} ?: LocalDate.parse(measuredDate).atTime(LocalTime.now(PlanDomain.zone)).atZone(PlanDomain.zone).toOffsetDateTime()))}catch(e:Exception){error=e.message}}){Icon(Icons.Outlined.Check,"Save weight")}}
 TextButton(onClick={scope.launch{try{imported=importer.weightCandidates()}catch(e:Exception){error=e.message}}}){Text("Choose tracker measurement")}
 imported.forEach{w->OutlinedButton(enabled=!busy,onClick={val at=w.recordedAt.atZone(PlanDomain.zone).toOffsetDateTime();save(PlanDomain.weightPayload(java.math.BigDecimal.valueOf(w.weightLb).setScale(2,java.math.RoundingMode.HALF_UP).toPlainString(),at,"tracker").put("source_key",w.sourceKey),newImported=true)}){Text("${w.recordedAt.atZone(PlanDomain.zone).format(DateTimeFormatter.ofPattern("MMM d · h:mm a"))} · ${"%.1f".format(w.weightLb)} lb")}}
 if(activity!=null) TextButton(enabled=!busy,onClick={scope.launch{try{repo.complete(activity,"skipped");onSaved()}catch(e:Exception){error=e.message}}}){Text("Skip")}
 error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
 val weights=records.filter{!it.deleted && it.type=="weight_entries"}.sortedByDescending{JSONObject(it.payload).optString("measured_at")}
 var showMeasurements by remember{mutableStateOf(false)}
 if(weights.isNotEmpty())TextButton(onClick={showMeasurements=!showMeasurements}){Text("Measurements");Icon(if(showMeasurements)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null)}
 if(showMeasurements)weights.forEach{r->val p=JSONObject(r.payload);Row(Modifier.fillMaxWidth()){
  Text("${p.optString("local_date")} · ${p.optString("weight_lb")} lb",Modifier.weight(1f))
  IconButton(enabled=!busy,onClick={id=r.id;originalPayload=r.payload;baseRevision=r.revision;value=p.optString("weight_lb");measuredDate=p.getString("local_date");originalTime=p.getString("measured_at")}){Icon(Icons.Outlined.Edit,"Edit weight")}
  IconButton(enabled=!busy,onClick={scope.launch{try{repo.delete(r.type,r.id,expectedRevision=r.revision,expectedPayload=r.payload)}catch(e:Exception){error=e.message}}}){Icon(Icons.Outlined.DeleteOutline,"Delete weight")}
 }}
}
data class WeightPoint(val instant:Instant,val value:Double)
object WeightDisplay {
 fun coveredDates(week:LocalDate,today:LocalDate)=(0L..6L).map{week.plusDays(it)}.filter{it<=today}
 fun points(records:List<Record>)=records.filter{!it.deleted && it.type=="weight_entries"}.mapNotNull{r->runCatching{val p=JSONObject(r.payload);val value=p.getString("weight_lb").toDouble();require(value.isFinite() && value>0);WeightPoint(OffsetDateTime.parse(p.getString("measured_at")).toInstant(),value)}.getOrNull()}.filter{!it.instant.isAfter(Instant.now())}.distinct().sortedBy{it.instant}
 fun weeklyRecords(records:List<Record>,week:LocalDate)=records.filter { r -> r.type=="weight_entries" && !r.deleted && runCatching { OffsetDateTime.parse(JSONObject(r.payload).getString("measured_at")).atZoneSameInstant(PlanDomain.zone).toLocalDate()<week.plusWeeks(1) }.getOrDefault(false) }.sortedBy{JSONObject(it.payload).getString("measured_at")}
 fun trend(points:List<WeightPoint>):List<WeightPoint> {
  val days=points.groupBy{it.instant.atZone(PlanDomain.zone).toLocalDate()}.toSortedMap()
  return days.mapNotNull { (day,readings) ->
   val window=days.filterKeys{it>day.minusDays(7) && it<=day}
   if(window.size<2)null else WeightPoint(readings.maxOf{it.instant},window.values.map{daily->daily.map{it.value}.average()}.average())
  }
 }
 fun merge(records:List<Record>,imported:List<ImportedWeight>):List<Record> {
  val owned=records.filter{it.type=="weight_entries"}.map{JSONObject(it.payload).optString("source_key")}.toSet()
  return records+imported.filter{it.sourceKey !in owned}.map{w->Record("weight_entries","chart:"+w.sourceKey,0,JSONObject().put("measured_at",w.recordedAt.toString()).put("weight_lb",w.weightLb).toString())}
 }

}
@Composable fun WeightGraph(records:List<Record>) {
 val points=WeightDisplay.points(records)
 if(points.isEmpty()){Text("No weight measurements",style=MaterialTheme.typography.bodyMedium,color=Color.Gray);return}
 val trend=WeightDisplay.trend(points)
 val first=points.first();val last=points.last();val format=DateTimeFormatter.ofPattern("MMM d")
 Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.Bottom){
   Column(Modifier.weight(1f)){Text("${"%.1f".format(last.value)} lb",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold);Text(last.instant.atZone(PlanDomain.zone).format(format),style=MaterialTheme.typography.bodySmall,color=Color.Gray)}
   if(trend.lastOrNull()?.instant==last.instant)Column(Modifier.weight(1f),horizontalAlignment=Alignment.End){Text("${"%.1f".format(trend.last().value)} lb",style=MaterialTheme.typography.titleLarge,color=EurosColors.Blue);Text("7-day average",style=MaterialTheme.typography.bodySmall,color=Color.Gray)}
  }
  Surface(color=Color(0xFFF4F7FC),shape=RoundedCornerShape(10.dp)) {
   Canvas(Modifier.fillMaxWidth().semantics { contentDescription=points.joinToString("; "){"${it.instant.atZone(PlanDomain.zone).format(format)}: ${"%.1f".format(it.value)} lb"} }.height(160.dp).padding(horizontal=12.dp,vertical=15.dp)){
    val min=points.minOf{it.value}-1;val range=(points.maxOf{it.value}-min+1).coerceAtLeast(1.0)
    val width=(last.instant.toEpochMilli()-first.instant.toEpochMilli()).coerceAtLeast(1)
    fun position(p:WeightPoint)=Offset(if(points.size==1)size.width/2 else 6.dp.toPx()+((p.instant.toEpochMilli()-first.instant.toEpochMilli()).toDouble()/width*(size.width-12.dp.toPx())).toFloat(),(size.height-6.dp.toPx()-((p.value-min)/range*(size.height-12.dp.toPx()))).toFloat())
    for(fraction in listOf(0f,0.5f,1f))drawLine(Color(0xFFDCE3EF),Offset(0f,size.height*fraction),Offset(size.width,size.height*fraction),1f)
    points.forEach{p->drawCircle(Color(0xFF94AFE0),3.dp.toPx(),position(p))}
    trend.forEach{drawCircle(EurosColors.Blue,2.dp.toPx(),position(it))}
    trend.zipWithNext().forEach{(a,b)->if(Duration.between(a.instant,b.instant).toDays()<7)drawLine(EurosColors.Blue,position(a),position(b),3.dp.toPx(),cap=androidx.compose.ui.graphics.StrokeCap.Round)}

   }
  }
  Text("${points.size} measurements · blue line: 7-day average",style=MaterialTheme.typography.labelSmall,color=EurosColors.Muted)
  if(points.size>1)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(first.instant.atZone(PlanDomain.zone).format(format),style=MaterialTheme.typography.labelSmall,color=Color.Gray);Text(last.instant.atZone(PlanDomain.zone).format(format),style=MaterialTheme.typography.labelSmall,color=Color.Gray)}
 }
}
@Composable fun WeeklyReview(repo:Repository,records:List<Record>,week:LocalDate,onGenerate:((LocalDate,List<Record>)->Unit)?=null,onClose:()->Unit) {
 val chartContext=LocalContext.current
 val healthSnapshot by repo.dao.observeMeta(HealthImport.KEY).collectAsState(initial=null)
 var chartRecords by remember{mutableStateOf(records)}
 LaunchedEffect(records,healthSnapshot){
  try { chartRecords=WeightDisplay.merge(repo.dao.recordsIncludingDeleted(),HealthImport(chartContext,repo.dao).weightCandidates()) }
  catch(e:kotlinx.coroutines.CancellationException){throw e}
  catch(_:Exception){chartRecords=records}
 }
 var weighing by rememberSaveable(week.toString()){mutableStateOf(false)}
 val canGenerate=WeeklyReviewCache.canGenerate(week,LocalDate.now(PlanDomain.zone))
 val fingerprint=WeeklyReviewCache.fingerprint(week,records,LocalDate.now(PlanDomain.zone))
 val cached by repo.dao.observeMeta(WeeklyReviewCache.key(week)).collectAsState(initial=null)
 val jobRaw by repo.dao.observeMeta(WeeklyReviewWork.key(week)).collectAsState(initial=null)
 val job=remember(jobRaw){jobRaw?.let{runCatching{JSONObject(it)}.getOrNull()}}
 val generating=job?.optString("status") in setOf("queued","running")
 var response by remember(week){mutableStateOf<JSONObject?>(null)}
 var responseFingerprint by remember(week){mutableStateOf<String?>(null)}
 var loadingCache by remember(week){mutableStateOf(true)}
 var localError by remember(week){mutableStateOf<String?>(null)}
 val error=localError ?: job?.takeIf{it.optString("status")=="failed"}?.optString("error")
 val scope=rememberCoroutineScope()
 fun generate() {
  localError=null
  if(onGenerate!=null)onGenerate(week,records.toList())
  else scope.launch {
   try{WeeklyReviewWork.enqueue(chartContext,repo,week,records.toList())}
   catch(e:kotlinx.coroutines.CancellationException){throw e}
   catch(e:Exception){localError=WeeklyReviewCache.errorMessage(e)}
  }
 }
 LaunchedEffect(week,cached) {
  try {
   val raw=cached ?: repo.dao.meta(WeeklyReviewCache.key(week))
   if(raw!=null) {
    val saved=JSONObject(raw)
    val savedFingerprint=saved.getString("fingerprint")
    WeeklyReviewCache.decode(raw,savedFingerprint,week)?.let{response=it;responseFingerprint=savedFingerprint}
   }
  } catch(e:kotlinx.coroutines.CancellationException){throw e}
  catch(_:Exception){localError="Couldn’t load the saved wrap-up."}
  finally{loadingCache=false}
 }
 Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
  Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
   Text("Weekly wrap-up",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
   Text(weekTitle(week),style=MaterialTheme.typography.bodyMedium,color=EurosColors.Muted)
  }
  if(response!=null && !generating && !loadingCache) IconButton(onClick={generate()}) {Icon(Icons.Outlined.Refresh,"Refresh wrap-up",tint=EurosColors.Blue)}
  IconButton(onClick=onClose){Icon(Icons.Outlined.Close,"Close")}
 }
 if(!canGenerate) Text("No logs for this week yet",color=EurosColors.Muted)
 else if(loadingCache || generating) {
  Surface(color=EurosColors.Soft,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()) {
   Row(Modifier.padding(18.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
    CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
     Text(if(generating) "Reviewing your week…" else "Loading wrap-up…",style=MaterialTheme.typography.bodyMedium)
    }
   }
  }
 }
 if(response!=null && responseFingerprint!=fingerprint && !generating) Text("Logs updated since this recap. Refresh for the latest.",style=MaterialTheme.typography.bodySmall,color=EurosColors.Muted)
 response?.let { review ->
  SelectionContainer {
   Column(verticalArrangement=Arrangement.spacedBy(18.dp)) {
    Surface(color=EurosColors.Soft,shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth()) {
     Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
      review.optString("headline").takeIf{it.isNotBlank()}?.let { Text(it,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold) }
      Text(review.getString("summary"),style=MaterialTheme.typography.bodyLarge)
     }
    }
    val insights=review.optJSONArray("insights")
    if(insights!=null)for(i in 0 until insights.length()) {
     val insight=insights.getJSONObject(i)
     Row(Modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top) {
      Surface(color=EurosColors.Paper,shape=RoundedCornerShape(12.dp)) {
       Icon(when(insight.getString("kind")){"training"->Icons.Outlined.SportsMartialArts;"recovery"->Icons.Outlined.Spa;"food"->Icons.Outlined.Restaurant;else->Icons.Outlined.FavoriteBorder},null,Modifier.padding(10.dp).size(20.dp),tint=EurosColors.Blue)
      }
      Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
       Text(insight.getString("title"),style=MaterialTheme.typography.titleMedium)
       Text(insight.getString("body"),style=MaterialTheme.typography.bodyMedium,color=EurosColors.Muted)
      }
     }
    }
    val adjustments=review.getJSONArray("adjustments")
    if(adjustments.length()>0) {
     Surface(color=EurosColors.Paper,shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth()) {
      Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
       Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.ArrowForward,null,tint=EurosColors.Blue,modifier=Modifier.size(20.dp))
        Text("Next week",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
       }
       for(i in 0 until adjustments.length()) {
        if(i>0)HorizontalDivider(color=EurosColors.Line)
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top) {
         Surface(color=Color.White,shape=RoundedCornerShape(8.dp)) {
          Box(Modifier.size(28.dp),contentAlignment=Alignment.Center) {
           Text("${i+1}",style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.SemiBold,color=EurosColors.Blue)
          }
         }
         Text(adjustments.getString(i),style=MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(1f).padding(top=3.dp))
        }
       }
      }
     }
    }
   }
  }
 }
 if(canGenerate && !loadingCache && !generating && response==null) {
  Surface(color=EurosColors.Paper,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()) {
   Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
    Text("Review your logged training, recovery and food for the week.",style=MaterialTheme.typography.bodyLarge)
    error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)}
    Button(onClick={generate()},shape=RoundedCornerShape(12.dp)) {
     Icon(Icons.Outlined.Insights,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp))
     Text(if(error!=null) "Retry" else "Generate wrap-up")
    }
   }
  }
 } else if(error!=null) {
  Text(error!!,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)
  TextButton(onClick={generate()},enabled=!generating){Text("Retry")}
 }
 Surface(color=EurosColors.Paper,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()) {
  Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
    Icon(Icons.Outlined.MonitorWeight,null,tint=EurosColors.Muted,modifier=Modifier.size(20.dp));Spacer(Modifier.width(8.dp))
    Text("Weight",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
    IconButton(onClick={weighing=!weighing}){Icon(if(weighing)Icons.Outlined.Close else Icons.Outlined.Edit,if(weighing)"Close weight entry" else "Log weight",tint=EurosColors.Blue)}
   }
   WeightGraph(WeightDisplay.weeklyRecords(chartRecords,week))
   if(weighing)WeightPanel(repo,records.filter{it.type!="weight_entries" || JSONObject(it.payload).optString("local_date")<=week.plusDays(6).toString()},week.plusDays(6),onSaved={weighing=false},showGraph=false)
  }
 }

}
