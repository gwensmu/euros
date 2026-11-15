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
