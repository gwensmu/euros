package com.gwensmuda.euros

import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.common.DownloadStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit instrumentation diagnostic: synthetic input only; model download requires explicit instrumentation flag. No repository writes. */
@RunWith(AndroidJUnit4::class)
class OnDeviceProbeTest {
 @Test fun probeAndSyntheticEvaluation() = runBlocking<Unit> {
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val args=InstrumentationRegistry.getArguments()
  val report=JSONObject().put("schema_version",1).put("synthetic_only",true).put("download_requested",false)
  val results=JSONArray();report.put("results",results)
  ActivityScenario.launch(MainActivity::class.java).use { scenario ->
   val keepScreenOn=args.getString("benchmark","false")=="true"
   if(keepScreenOn) scenario.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
   val interpreter=NanoDraftInterpreter()
   try {
    var availability=interpreter.availability()
    report.put("status",availability.state)
    if(args.getString("download","false")=="true" && availability.state=="downloadable") {
     report.put("download_requested",true)
     val downloadEvents=JSONArray();report.put("download_events",downloadEvents)
     val downloadClient=Generation.getClient()
     try {
      withTimeout(120_000) {
       downloadClient.download().collect { progress ->
        val event=JSONObject()
        when(progress) {
         is DownloadStatus.DownloadStarted -> event.put("state","started").put("bytes_to_download",progress.bytesToDownload)
         is DownloadStatus.DownloadProgress -> event.put("state","progress").put("bytes_downloaded",progress.totalBytesDownloaded)
         is DownloadStatus.DownloadCompleted -> event.put("state","completed")
         is DownloadStatus.DownloadFailed -> event.put("state","failed").put("error",progress.e.message)
        }
        downloadEvents.put(event);persist(report)
        instrumentation.sendStatus(0,Bundle().apply{putString("stream","DOWNLOAD "+event.toString()+"\n")})
       }
      }
     } finally { downloadClient.close() }
     availability=interpreter.availability();report.put("status",availability.state)
    }
    if(availability.canGenerate && args.getString("benchmark","false")=="true") {
     instrumentation.waitForIdleSync()
     delay(2_000)
     report.put("foreground",foreground(scenario))
     val fixture=JSONObject(instrumentation.context.assets.open("model-evaluation.json").bufferedReader().use{it.readText()})
     val cases=fixture.getJSONArray("cases")
     val iterations=args.getString("iterations","1")!!.toInt().coerceIn(1,3)
     val limit=args.getString("limit",cases.length().toString())!!.toInt().coerceIn(1,cases.length())
     evaluation@ for(repeat in 1..iterations) for(index in 0 until limit) {
      val case=cases.getJSONObject(index)
      val result=JSONObject().put("id",case.getString("id")).put("iteration",repeat)
      val started=android.os.SystemClock.elapsedRealtime()
      val foregroundState=foreground(scenario)
      result.put("foreground",foregroundState)
      if(!foregroundState.getBoolean("ready")) {
       result.put("passed",false).put("error","Foreground unavailable; model not called")
       results.put(result);report.put("benchmark_blocked","foreground_unavailable");persist(report)
       instrumentation.sendStatus(0,Bundle().apply{putString("stream",result.toString()+"\n")})
       break@evaluation
      }
      try {
       val draft=interpreter.interpret(case.getString("kind"),case.getString("text"))
       result.put("output",draft.json).put("raw",draft.rawResponse).put("elapsed_ms",draft.elapsedMillis)
       result.put("passed",sameJson(draft.json,case.getJSONObject("expected")))
      } catch(e:Exception) {
       result.put("passed",false).put("error",e.javaClass.simpleName+": "+e.message).put("elapsed_ms",android.os.SystemClock.elapsedRealtime()-started)
       if(e is InvalidOnDeviceDraft) result.put("raw",e.rawResponse)
      }
      results.put(result)
      persist(report)
      instrumentation.sendStatus(0,Bundle().apply{putString("stream",result.toString()+"\n")})
     }
     report.put("quality_gate_passed",iterations==3 && limit==cases.length() && results.length()==iterations*limit && (0 until results.length()).all{results.getJSONObject(it).optBoolean("passed")})
    }
   } catch(e:Exception) { report.put("status","error").put("error",e.javaClass.simpleName+": "+e.message) }
   finally {
    interpreter.close();persist(report)
    if(keepScreenOn) scenario.onActivity { it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
   }
  }
  instrumentation.sendStatus(0,Bundle().apply{putString("stream","ON_DEVICE_REPORT "+report.toString()+"\n")})
 }
 private fun foreground(scenario:ActivityScenario<MainActivity>):JSONObject {
  val state=JSONObject()
  scenario.onActivity { activity ->
   val resumed=activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
   val focused=activity.hasWindowFocus()
   val interactive=activity.getSystemService(android.os.PowerManager::class.java).isInteractive
   val locked=activity.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
   val process=android.app.ActivityManager.RunningAppProcessInfo()
   android.app.ActivityManager.getMyMemoryState(process)
   val processForeground=process.importance<=android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
   state.put("resumed",resumed).put("window_focused",focused).put("interactive",interactive).put("keyguard_locked",locked).put("process_importance",process.importance)
   state.put("ready",resumed && focused && interactive && !locked && processForeground)
  }
  return state
 }
 private fun persist(report:JSONObject) { File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"on-device-synthetic-report.json").writeText(report.toString(2)) }
 private fun sameJson(a:Any?,b:Any?):Boolean = when {
  a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet()==b.keys().asSequence().toSet() && a.keys().asSequence().all{sameJson(a.get(it),b.get(it))}
  a is JSONArray && b is JSONArray -> a.length()==b.length() && (0 until a.length()).all{sameJson(a.get(it),b.get(it))}
  a is Number && b is Number -> a.toDouble()==b.toDouble()
  else -> a==b
 }
}
