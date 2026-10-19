package com.gwensmuda.euros

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

@Composable fun HomeHealthSync(repo:Repository,snackbar:SnackbarHostState) {
 val context=LocalContext.current
 val health=remember(repo){HealthImport(context.applicationContext,repo.dao)}
 val scope=rememberCoroutineScope()
 var busy by remember{mutableStateOf(false)}
 IconButton(enabled=!busy,onClick={
  if(!busy) {
   busy=true
   scope.launch {
    val message=try {
     val result=withContext(Dispatchers.IO) {
      val now=Instant.now()
      health.refreshFrom(maxOf(HealthSources.requestedStart(),now.minus(Duration.ofDays(30))),now)
     }
     if(result.messages.isEmpty()) "Tracker records synced" else result.messages.joinToString(" · ")+". Check Health Connect access in Settings."
    } catch(e:CancellationException){throw e}
    catch(_:Exception){"Couldn't sync trackers. Check Health Connect in Settings and try again."}
    finally{busy=false}
    snackbar.showSnackbar(message)
   }
  }
 }) {
  if(busy) CircularProgressIndicator(Modifier.size(20.dp).semantics{contentDescription="Syncing Health Connect"},strokeWidth=2.dp)
  else Icon(Icons.Outlined.Sync,contentDescription="Sync Health Connect",modifier=Modifier.size(20.dp))
 }
}
