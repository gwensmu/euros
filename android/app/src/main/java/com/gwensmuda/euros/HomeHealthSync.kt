package com.gwensmuda.euros

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

@Composable fun HomeHealthSync(repo:Repository) {
 val context=LocalContext.current
 val health=remember(repo){HealthImport(context.applicationContext,repo.dao)}
 val scope=rememberCoroutineScope()
 var busy by remember{mutableStateOf(false)}
 var message by rememberSaveable{mutableStateOf<String?>(null)}
 TextButton(enabled=!busy,onClick={
  if(!busy) {
   busy=true;message=null
   scope.launch {
    try {
     val result=withContext(Dispatchers.IO) {
      val now=Instant.now()
      health.refreshFrom(maxOf(HealthSources.requestedStart(),now.minus(Duration.ofDays(30))),now)
     }
     message=if(result.messages.isEmpty()) "Tracker records synced" else result.messages.joinToString(" · ")+". Check Health Connect access in Settings."
    } catch(e:CancellationException){throw e}
    catch(_:Exception){message="Couldn't sync trackers. Check Health Connect in Settings and try again."}
    finally{busy=false}
   }
  }
 }) {
  if(busy) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp)
  else Icon(Icons.Outlined.Sync,contentDescription=null,modifier=Modifier.size(18.dp))
  Spacer(Modifier.width(8.dp))
  Text(if(busy) "Syncing Health Connect…" else "Sync Health Connect")
 }
 message?.let{Text(it,style=MaterialTheme.typography.bodySmall,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})}
}
