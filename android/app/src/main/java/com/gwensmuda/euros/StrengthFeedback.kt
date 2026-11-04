package com.gwensmuda.euros

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Subjective session feedback, never an observed load, rep count or numeric RPE. */
enum class StrengthFeeling(val wire:String,val label:String) {
 EASY("easy","Easy"), ABOUT_RIGHT("about_right","About right"), TOO_HARD("too_hard","Too hard");
 companion object { fun fromWire(value:String)=entries.firstOrNull{it.wire==value} }
}
object StrengthFeedback {
 fun value(feeling:StrengthFeeling,source:String)=JSONObject().put("feeling",feeling.wire).put("source",source)
 fun validate(p:JSONObject) {
  if(!p.has("strength_feedback"))return
  val f=p.getJSONObject("strength_feedback")
  require(p.optString("kind")=="weights" && p.optString("status")=="done") { "Feedback requires a completed weights session" }
  require(f.keys().asSequence().toSet()==setOf("feeling","source"))
  require(StrengthFeeling.fromWire(f.getString("feeling"))!=null)
  require(f.getString("source") in setOf("default","selected"))
  require(f.getString("source")!="default" || f.getString("feeling")==StrengthFeeling.ABOUT_RIGHT.wire)
 }
}

@Composable fun StrengthFeedbackScreen(record:Record,repo:Repository,onClose:()->Unit) {
 val scope=rememberCoroutineScope()
 val completion=remember(record.id){record}
 var busy by remember { mutableStateOf(false) }
 var error by remember { mutableStateOf<String?>(null) }
 BackHandler { if(!busy)onClose() }
 Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
  IconButton(onClick=onClose,enabled=!busy){Icon(Icons.Outlined.ArrowBack,"Back")}
  Column(Modifier.weight(1f)) {
   Text(PlanDomain.payload(record).getString("title").replaceFirstChar{it.titlecase()},style=MaterialTheme.typography.headlineSmall)
   Text(PlanDomain.day(record).format(java.time.format.DateTimeFormatter.ofPattern("EEEE"))+" · "+activityTime(record),style=MaterialTheme.typography.bodyMedium,color=EurosColors.Muted)
  }
 }
 Text("How did it feel?",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.padding(top=12.dp,bottom=4.dp))
 Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
  StrengthFeeling.entries.forEach { feeling ->
   Surface(onClick={if(!busy){busy=true;scope.launch {
    try {repo.saveStrengthFeedback(completion,feeling);onClose()}
    catch(e:kotlinx.coroutines.CancellationException){throw e}
    catch(e:Exception){error=e.message ?: "Couldn't save feedback. Try again."}
    finally {busy=false}
   }}},enabled=!busy,color=EurosColors.Soft,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth()) {
    Row(Modifier.padding(horizontal=18.dp,vertical=18.dp).heightIn(min=24.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
     Text(feeling.label,style=MaterialTheme.typography.bodyLarge,modifier=Modifier.weight(1f))
     Icon(when(feeling){StrengthFeeling.EASY->Icons.Outlined.NorthEast;StrengthFeeling.ABOUT_RIGHT->Icons.Outlined.DragHandle;StrengthFeeling.TOO_HARD->Icons.Outlined.SouthEast},null,tint=EurosColors.Blue)
    }
   }
  }
 }
 error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
}
