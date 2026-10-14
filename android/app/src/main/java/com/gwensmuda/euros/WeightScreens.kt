package com.gwensmuda.euros

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
 fun points(records:List<Record>)=records.filter{!it.deleted && it.type=="weight_entries"}.mapNotNull{r->runCatching{val p=JSONObject(r.payload);val value=p.getString("weight_lb").toDouble();require(value.isFinite() && value>0);WeightPoint(OffsetDateTime.parse(p.getString("measured_at")).toInstant(),value)}.getOrNull()}.sortedBy{it.instant}
 fun weeklyRecords(records:List<Record>,week:LocalDate)=records.filter{r->r.type=="weight_entries" && !r.deleted && JSONObject(r.payload).optString("weekly_anchor").let{it.isNotBlank() && it<=week.toString()}}.sortedBy{JSONObject(it.payload).optString("weekly_anchor")}.takeLast(2)
}
@Composable fun WeightGraph(records:List<Record>) {
 val points=WeightDisplay.points(records)
 if(points.isEmpty()){Text("No weight measurements",style=MaterialTheme.typography.bodyMedium,color=Color.Gray);return}
 val first=points.first();val last=points.last();val format=DateTimeFormatter.ofPattern("MMM d")
 Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(10.dp)) {
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.Bottom){
   Column{Text("${"%.1f".format(last.value)} lb",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.SemiBold);Text(last.instant.atZone(PlanDomain.zone).format(format),style=MaterialTheme.typography.bodySmall,color=Color.Gray)}
   if(points.size>1)Column(horizontalAlignment=Alignment.End){Text("${"%+.1f".format(last.value-first.value)} lb",style=MaterialTheme.typography.titleLarge);Text("since ${first.instant.atZone(PlanDomain.zone).format(format)}",style=MaterialTheme.typography.bodySmall,color=Color.Gray)}
  }
  Surface(color=Color(0xFFF4F7FC),shape=RoundedCornerShape(10.dp)) {
   Canvas(Modifier.fillMaxWidth().height(118.dp).padding(horizontal=12.dp,vertical=15.dp)){
    val min=points.minOf{it.value}-1;val range=(points.maxOf{it.value}-min+1).coerceAtLeast(1.0)
    val width=(last.instant.toEpochMilli()-first.instant.toEpochMilli()).coerceAtLeast(1)
    drawLine(Color(0xFFDCE3EF),Offset(0f,size.height),Offset(size.width,size.height),1f)
    points.forEach{p->drawCircle(Color(0xFF2563EB),5.dp.toPx(),Offset(if(points.size==1)size.width/2 else 6.dp.toPx()+((p.instant.toEpochMilli()-first.instant.toEpochMilli()).toDouble()/width*(size.width-12.dp.toPx())).toFloat(),(size.height-6.dp.toPx()-((p.value-min)/range*(size.height-12.dp.toPx()))).toFloat()))}
   }
  }
  if(points.size>1)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(first.instant.atZone(PlanDomain.zone).format(format),style=MaterialTheme.typography.labelSmall,color=Color.Gray);Text(last.instant.atZone(PlanDomain.zone).format(format),style=MaterialTheme.typography.labelSmall,color=Color.Gray)}
 }
}
@Composable fun WeeklyReview(repo:Repository,records:List<Record>,week:LocalDate,onClose:()->Unit) {
 var weighing by rememberSaveable(week.toString()){mutableStateOf(false)}
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
  Column{Text("${week.format(DateTimeFormatter.ofPattern("MMM d"))}–${week.plusDays(6).format(DateTimeFormatter.ofPattern("MMM d"))}",style=MaterialTheme.typography.labelMedium,color=Color.Gray);Text("Weekly wrap-up",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)}
  IconButton(onClick=onClose){Icon(Icons.Outlined.Close,"Close")}
 }
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("Sunday weight",style=MaterialTheme.typography.titleMedium);IconButton(onClick={weighing=!weighing}){Icon(if(weighing)Icons.Outlined.Close else Icons.Outlined.Edit,if(weighing)"Close weight entry" else "Log weight")}}
 WeightGraph(WeightDisplay.weeklyRecords(records,week))
 if(weighing)WeightPanel(repo,records.filter{it.type!="weight_entries" || JSONObject(it.payload).optString("local_date")<=week.plusDays(6).toString()},week.plusDays(6),onSaved={weighing=false},showGraph=false)
 val activities=PlanDomain.rootActivities(records).filter{PlanDomain.day(it)>=week && PlanDomain.day(it)<week.plusWeeks(1)}
 val done=activities.filter{PlanDomain.progress(it,records)=="done"}
 val bjj=done.count{PlanDomain.payload(it).optString("kind")=="bjj"};val weights=done.count{PlanDomain.payload(it).optString("kind")=="weights"}
 val foodDays=records.filter{it.type=="food_entries" && !it.deleted}.map{JSONObject(it.payload).optString("local_date")}.filter{it>=week.toString() && it<=week.plusDays(6).toString()}.distinct().size
 HorizontalDivider(color=Color(0xFFDCE3EF))
 Text("This week",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
 if(done.isNotEmpty()) {
  if(bjj>0)Text("$bjj BJJ session${if(bjj==1)"" else "s"} logged.")
  if(weights>0)Text("$weights weights session${if(weights==1)"" else "s"} completed.")
  val recovery=done.count{PlanDomain.payload(it).optString("kind") in setOf("recovery","rest")}
  if(recovery>0)Text("$recovery recovery routine${if(recovery==1)"" else "s"} completed.")
 } else Text("No sessions logged this week.",color=Color.Gray)
 if(foodDays>0)Text("Food logged on $foodDays day${if(foodDays==1)"" else "s"}.")
 val covered=WeightDisplay.coveredDates(week,LocalDate.now(PlanDomain.zone))
 val incomplete=activities.count{PlanDomain.day(it) in covered && !PlanDomain.resolved(it,records)}
 val coveredFoodDays=records.filter{it.type=="food_entries" && !it.deleted}.map{JSONObject(it.payload).optString("local_date")}.distinct().count{it in covered.map(LocalDate::toString)}
 HorizontalDivider(color=Color(0xFFDCE3EF))
 if(covered.isNotEmpty())Text("$incomplete activities without a complete log · $coveredFoodDays of ${covered.size} days with food entries",style=MaterialTheme.typography.bodySmall,color=Color.Gray)
}
