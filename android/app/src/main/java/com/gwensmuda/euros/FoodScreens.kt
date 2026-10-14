package com.gwensmuda.euros

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Composable fun FoodSummaryBar(records:List<Record>,date:LocalDate,targets:JSONObject?,onClick:()->Unit={}) {
 val totals=FoodDomain.summary(records,date)
 val calories=FoodDomain.targetLabel(targets,"kcal") ?: "—"
 fun shown(total:NutrientTotal)=total.display()+if(total.partial && total.known!=null) "+" else ""
 val text="${shown(totals.kcal)} / $calories kcal  ·  ${shown(totals.protein)}g protein  ·  ${shown(totals.carbs)}g carbs  ·  ${shown(totals.fat)}g fat"
 Text(text,fontSize=12.sp,color=Color(0xFF454C58),modifier=Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=8.dp).semantics {
  contentDescription=text+if(listOf(totals.kcal,totals.protein,totals.carbs,totals.fat).any{it.partial}) ". Includes entries with unknown nutrient values." else ""
 })
}

/** Parent owns the date/header and bottom navigation. Hide those while onEditorVisibility is true. */
@Composable fun FoodScreen(
 repo:Repository,
 records:List<Record>,
 date:LocalDate,
 targets:JSONObject?,
 mealReferences:List<FoodMealReference>,
 openEntry:Boolean=false,
 plannedActivityId:String?=null,
 onEntryOpened:()->Unit={},
 onEditorVisibility:(Boolean)->Unit={},
 mealChoices:List<FoodMealReference> = mealReferences,
) {
 var editingId by rememberSaveable { mutableStateOf<String?>(null) }
 var creating by rememberSaveable { mutableStateOf(false) }
 var expanded by rememberSaveable { mutableStateOf(false) }
 var entryActivityId by rememberSaveable {mutableStateOf<String?>(null)}
 LaunchedEffect(openEntry) { if(openEntry) {creating=true;editingId=null;entryActivityId=plannedActivityId;onEntryOpened()} }
 val editing=creating || editingId!=null
 LaunchedEffect(editing) {onEditorVisibility(editing)}
 DisposableEffect(Unit) {onDispose {onEditorVisibility(false)}}
 val entries=FoodDomain.entries(records,date)
 if(editing) {
  val existing=editingId?.let{id->records.find{it.type=="food_entries" && it.id==id && !it.deleted}}
  if(!creating && existing==null) {
   BackHandler {creating=false;editingId=null}
   Column {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){IconButton(onClick={creating=false;editingId=null}){Icon(Icons.Outlined.Close,"Close")}}
    Text("This entry was removed")
   }
  } else FoodEntryEditor(repo,date,existing,mealChoices,if(creating) entryActivityId else null) {creating=false;editingId=null}
  return
 }
 Column(verticalArrangement=Arrangement.spacedBy(18.dp)) {
  NutritionWidget(FoodDomain.summary(records,date),targets){creating=true;editingId=null;entryActivityId=null}
  Text("Keep eating",style=MaterialTheme.typography.titleLarge)
  val remaining=FoodDomain.remainingReferences(mealReferences,records,date)
  if(remaining.isEmpty()) Text("No remaining meals planned",color=MaterialTheme.colorScheme.onSurfaceVariant)
  remaining.forEach { meal ->
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top) {
    Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(8.dp)) {
     Icon(Icons.Outlined.Restaurant,null,Modifier.padding(8.dp).size(18.dp),tint=MaterialTheme.colorScheme.onSurface)
    }
    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
     Text(meal.title,style=MaterialTheme.typography.titleMedium)
     val amounts=listOfNotNull(meal.protein?.let{"${it}g protein"},meal.carbs?.let{"${it}g carbs"},meal.fat?.let{"${it}g fat"}).joinToString(" · ")
     meal.time?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
     if(amounts.isNotBlank()) Text(amounts,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
    }
   }
   HorizontalDivider(color=Color(0xFFE5EAF1))
  }
  if(entries.isNotEmpty()) {
   TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(0.dp)) {
    Text("Entries · ${entries.size}");Spacer(Modifier.width(8.dp));Icon(if(expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null)
   }
   if(expanded) entries.forEach { entry ->
    val draft=FoodDomain.draftFromRecord(entry)
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
     Column(Modifier.weight(1f)) {
      Text(draft.text.ifBlank{"Food entry"},style=MaterialTheme.typography.bodyLarge)
      Text(listOfNotNull(runCatching{OffsetDateTime.parse(draft.consumedAt).format(DateTimeFormatter.ofPattern("h:mm a"))}.getOrNull(),draft.grams.takeIf{it.isNotBlank()}?.let{"${it}g"},draft.kcal.takeIf{it.isNotBlank()}?.let{"$it kcal"}).joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
     }
     IconButton(onClick={editingId=entry.id;creating=false}){Icon(Icons.Outlined.Edit,"Edit ${draft.text}",tint=MaterialTheme.colorScheme.primary)}
    }
   }
  }
 }
}

@Composable private fun NutritionWidget(totals:NutritionTotals,targets:JSONObject?,onAdd:()->Unit) {
 val largeText=LocalDensity.current.fontScale>1.5f
 Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(8.dp),modifier=Modifier.fillMaxWidth()) {
  Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
   Row(verticalAlignment=Alignment.CenterVertically) {
    if(largeText) Column(Modifier.weight(1f)) {
     Text(totals.kcal.display(),style=MaterialTheme.typography.headlineMedium)
     Text("/ ${FoodDomain.targetLabel(targets,"kcal") ?: "—"} kcal",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    } else Row(Modifier.weight(1f),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
     Text(totals.kcal.display(),style=MaterialTheme.typography.headlineMedium)
     Text("/ ${FoodDomain.targetLabel(targets,"kcal") ?: "—"} kcal",modifier=Modifier.padding(bottom=3.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    FoodIconButton("Log food",onAdd){Icon(Icons.Outlined.Edit,null,Modifier.size(19.dp))}
   }
   NutrientProgress(totals.kcal,FoodDomain.targetNumber(targets,"kcal"),"Calories")
   if(largeText) Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
    MacroWidget("Protein",totals.protein,FoodDomain.targetNumber(targets,"proteinG"),Modifier.fillMaxWidth())
    MacroWidget("Carbs",totals.carbs,FoodDomain.targetNumber(targets,"carbohydrateG"),Modifier.fillMaxWidth())
    MacroWidget("Fat",totals.fat,FoodDomain.targetNumber(targets,"fatG"),Modifier.fillMaxWidth())
   } else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
    MacroWidget("Protein",totals.protein,FoodDomain.targetNumber(targets,"proteinG"),Modifier.weight(1f))
    MacroWidget("Carbs",totals.carbs,FoodDomain.targetNumber(targets,"carbohydrateG"),Modifier.weight(1f))
    MacroWidget("Fat",totals.fat,FoodDomain.targetNumber(targets,"fatG"),Modifier.weight(1f))
   }
   if(totals.entries>0 && listOf(totals.kcal,totals.protein,totals.carbs,totals.fat).any{it.partial}) Text("Some totals are incomplete",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }
 }
}
@Composable private fun MacroWidget(label:String,total:NutrientTotal,target:BigDecimal?,modifier:Modifier) {
 Column(modifier,verticalArrangement=Arrangement.spacedBy(5.dp)) {
  Text("${total.display()}g",style=MaterialTheme.typography.titleMedium)
  Text(label,style=MaterialTheme.typography.bodySmall)
  NutrientProgress(total,target,label)
 }
}
@Composable private fun NutrientProgress(total:NutrientTotal,target:BigDecimal?,label:String) {
 val fraction=if(target!=null && target>BigDecimal.ZERO && total.known!=null) (total.known.toDouble()/target.toDouble()).toFloat().coerceIn(0f,1f) else 0f
 LinearProgressIndicator(progress={fraction},modifier=Modifier.fillMaxWidth().height(4.dp).semantics{contentDescription="$label ${total.display()}${target?.let{" of ${FoodDomain.displayDecimal(it)}"} ?: ", target unset"}${if(total.partial) ", incomplete" else ""}"},color=MaterialTheme.colorScheme.primary,trackColor=Color(0xFFDDE7F9))
}

@Composable private fun FoodEntryEditor(repo:Repository,date:LocalDate,existing:Record?,meals:List<FoodMealReference>,plannedActivityId:String?,onClose:()->Unit) {
 val key="food:${existing?.id ?: "new:$date:${plannedActivityId ?: "free"}"}"
 var draft by remember(key) { mutableStateOf<FoodDraft?>(null) }
 var errors by remember {mutableStateOf<Map<String,String>>(emptyMap())}
 var problem by remember {mutableStateOf<String?>(null)}
 var reviewed by rememberSaveable(key) {mutableStateOf(false)}
 var busy by remember {mutableStateOf(false)}
 var deleteConfirm by remember {mutableStateOf(false)}
 var mealMenu by remember {mutableStateOf(false)}
 var details by rememberSaveable(key) {mutableStateOf(false)}
 val scope=rememberCoroutineScope();val draftWrites=remember{Mutex()}
 LaunchedEffect(key) {
  runCatching {
   val saved=repo.loadDraft(key)
   draft=if(saved!=null) FoodDomain.loadDraft(saved) else existing?.let(FoodDomain::draftFromRecord) ?: FoodDomain.newDraft(date,plannedActivityId=plannedActivityId)
  }.onFailure{problem="Couldn't open this draft"}
 }
 fun update(next:FoodDraft) {
  draft=next;reviewed=false;errors=emptyMap()
  scope.launch { draftWrites.withLock { runCatching{repo.saveDraft(key,FoodDomain.saveDraft(next))}.onFailure{problem="Couldn't save this draft"} } }
 }
 fun close() {
  if(busy) return
  scope.launch {
   busy=true
   runCatching {draftWrites.withLock {draft?.let{repo.saveDraft(key,FoodDomain.saveDraft(it))}}}.onSuccess{onClose()}.onFailure{problem="Couldn't save this draft"}
   busy=false
  }
 }
 BackHandler{close()}
 Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(14.dp)) {
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {FoodIconButton("Close",{close()},!busy){Icon(Icons.Outlined.Close,null,Modifier.size(19.dp))}}
  val current=draft
  if(current==null) {Text(problem ?: "Opening…");return@Column}
  Text(runCatching{LocalDate.parse(current.localDate).format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))}.getOrDefault(current.dateInput).uppercase(),style=MaterialTheme.typography.labelSmall,letterSpacing=1.5.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Text("Log food",style=MaterialTheme.typography.headlineLarge)
  val changed=existing!=null && (existing.revision!=current.baseRevision || existing.payload!=current.originalPayload)
  if(changed) {
   Text("This entry changed",color=MaterialTheme.colorScheme.error)
   TextButton(onClick={update(FoodDomain.draftFromRecord(existing!!))},enabled=!busy){Text("Reload entry")}
  }
  if(!reviewed) {
   OutlinedTextField(current.text,{update(current.copy(text=it))},label={Text("What did you eat?")},isError=errors.containsKey("text"),supportingText=errors["text"]?.let{message->{Text(message)}},modifier=Modifier.fillMaxWidth(),minLines=4,shape=RoundedCornerShape(8.dp),enabled=!busy)
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
    TextButton(onClick={details=!details},contentPadding=PaddingValues(horizontal=0.dp),enabled=!busy) {Text("Details");Icon(if(details) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null)}
    Spacer(Modifier.weight(1f))
    Button(onClick={errors=FoodDomain.validate(current);if(errors.isEmpty()) reviewed=true else if(errors.keys.any{it!="text"}) details=true},enabled=!busy,shape=RoundedCornerShape(8.dp)){Text("Add")}
   }
   if(details) {
   HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    OutlinedTextField(current.dateInput,{update(current.copy(dateInput=it))},label={Text("Date · YYYY-MM-DD")},singleLine=true,enabled=!busy,modifier=Modifier.weight(1f),isError=errors.containsKey("time"))
    OutlinedTextField(current.timeInput,{update(current.copy(timeInput=it))},label={Text("Time · HH:mm")},singleLine=true,enabled=!busy,modifier=Modifier.weight(1f),isError=errors.containsKey("time"))
   }
   errors["time"]?.let{Text(it,color=MaterialTheme.colorScheme.error)}
   FoodNumberField("Quantity · g",current.grams,errors["grams"],!busy){update(current.copy(grams=it))}
   FoodNumberField("Calories · kcal",current.kcal,errors["kcal"],!busy){update(current.copy(kcal=it))}
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    Box(Modifier.weight(1f)){FoodNumberField("Protein · g",current.protein,errors["protein"],!busy){update(current.copy(protein=it))}}
    Box(Modifier.weight(1f)){FoodNumberField("Carbs · g",current.carbs,errors["carbs"],!busy){update(current.copy(carbs=it))}}
    Box(Modifier.weight(1f)){FoodNumberField("Fat · g",current.fat,errors["fat"],!busy){update(current.copy(fat=it))}}
   }
   Box {
    TextButton(onClick={mealMenu=true},enabled=!busy){Text(meals.find{it.id==current.mealReferenceId}?.title ?: "Meal");Icon(Icons.Outlined.ExpandMore,null)}
    DropdownMenu(expanded=mealMenu,onDismissRequest={mealMenu=false}) {
     DropdownMenuItem(text={Text("No planned meal")},onClick={update(current.copy(mealReferenceId=null));mealMenu=false})
     meals.forEach{meal->DropdownMenuItem(text={Text(meal.title)},onClick={update(current.copy(mealReferenceId=meal.id));mealMenu=false})}
    }
   }
   }
  } else {
   OutlinedCard(Modifier.fillMaxWidth(),shape=RoundedCornerShape(8.dp)) {
    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
     Row(verticalAlignment=Alignment.CenterVertically){Text(current.text,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);IconButton(onClick={reviewed=false;details=true},enabled=!busy){Icon(Icons.Outlined.Edit,"Edit food details")}}
     if(current.grams.isNotBlank()) Text("${current.grams}g")
     Text("${current.kcal.ifBlank{"—"}} kcal",style=MaterialTheme.typography.headlineSmall)
     Text("${current.protein.ifBlank{"—"}}g protein · ${current.carbs.ifBlank{"—"}}g carbs · ${current.fat.ifBlank{"—"}}g fat")
    }
   }
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
    FilledIconButton(enabled=!busy && !changed,onClick={scope.launch {
     busy=true;problem=null
     runCatching {
      draftWrites.withLock {repo.upsert("food_entries",current.id,FoodDomain.payload(current),expectedRevision=current.baseRevision,expectedPayload=current.originalPayload);repo.clearDraft(key)}
     }.onSuccess{onClose()}.onFailure{problem=it.message ?: "Couldn't save food"}
     busy=false
    }}) {Icon(Icons.Outlined.Check,"Save food")}
   }
  }
  if(existing!=null) TextButton(onClick={deleteConfirm=true},enabled=!busy && !changed){Text("Delete entry")}
  problem?.let{Text(it,color=MaterialTheme.colorScheme.error)}
 }
 if(deleteConfirm) AlertDialog(onDismissRequest={if(!busy) deleteConfirm=false},title={Text("Delete this entry?")},text={Text(existing?.let{FoodDomain.draftFromRecord(it).text}.orEmpty())},confirmButton={TextButton(enabled=!busy,onClick={scope.launch {
  busy=true;runCatching{draftWrites.withLock{repo.delete("food_entries",existing!!.id,expectedRevision=draft!!.baseRevision,expectedPayload=draft!!.originalPayload);repo.clearDraft(key)}}.onSuccess{onClose()}.onFailure{problem=it.message;deleteConfirm=false};busy=false
 }}){Text("Delete")}},dismissButton={TextButton(enabled=!busy,onClick={deleteConfirm=false}){Text("Cancel")}})
}
@Composable private fun FoodNumberField(label:String,value:String,error:String?,enabled:Boolean,onChange:(String)->Unit) {
 OutlinedTextField(value,onChange,label={Text(label)},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=enabled,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),isError=error!=null,supportingText=error?.let{message->{Text(message)}})
}

@Composable private fun FoodIconButton(label:String,onClick:()->Unit,enabled:Boolean=true,content:@Composable ()->Unit) {
 Surface(onClick=onClick,enabled=enabled,shape=RoundedCornerShape(8.dp),color=MaterialTheme.colorScheme.surface,contentColor=MaterialTheme.colorScheme.primary,border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),modifier=Modifier.size(44.dp).semantics{contentDescription=label}) {
  Box(contentAlignment=Alignment.Center){content()}
 }
}
