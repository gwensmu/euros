package com.gwensmuda.euros

import android.os.Bundle
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Explicit release verification on enrolled device; no fixtures or permission changes. */
@RunWith(AndroidJUnit4::class)
class LiveHealthVerificationTest {
 @get:Rule val activity=ActivityScenarioRule(MainActivity::class.java)
 @Before fun requireExplicitLiveCheck(){assumeTrue(InstrumentationRegistry.getArguments().getString("verifyLiveHealth")=="true")}
 @Test fun refreshUsingExistingGrants()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val app=context.applicationContext as EurosApplication
  val result=HealthImport(context,app.repository.dao).refresh()
  report("Health refresh passed; sessions=${result.sessions}; permission notices=${result.messages.size}")
 }
 @Test fun verifySavedNutritionAgainstPlatformReadback()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val dao=(context.applicationContext as EurosApplication).repository.dao
  check(dao.meta(NutritionExport.ENABLED)=="true"){"Enable food sharing before this readback check"}
  val client=HealthConnectClient.getOrCreate(context)
  val actual=mutableListOf<NutritionRecord>();var token:String?=null
  do {
   val page=client.readRecords(ReadRecordsRequest(NutritionRecord::class,TimeRangeFilter.between(Instant.EPOCH,Instant.now().plusSeconds(60)),dataOriginFilter=setOf(DataOrigin(context.packageName)),pageSize=1000,pageToken=token))
   actual.addAll(page.records);token=page.pageToken
  }while(token!=null)
  val byId=actual.groupBy{it.metadata.clientRecordId}
  val foods=dao.recordsIncludingDeleted().filter{it.type=="food_entries"}
  for(food in foods) {
   val observed=byId[NutritionExport.clientId(food.id)].orEmpty()
   if(food.deleted){assertTrue("Deleted food still exported",observed.isEmpty());continue}
   assertEquals("Expected one platform record per saved food",1,observed.size)
   val expected=NutritionExport.record(food,0);val record=observed.single()
   assertEquals(expected.startTime,record.startTime);assertEquals(expected.startZoneOffset,record.startZoneOffset)
   assertEquals(expected.energy,record.energy);assertEquals(expected.protein,record.protein)
   assertEquals(expected.totalCarbohydrate,record.totalCarbohydrate);assertEquals(expected.totalFat,record.totalFat)
  }
  report("Verified nutrition readback: ${foods.count{!it.deleted}} saved entries; no duplicates; tombstones absent")
 }
 private fun report(message:String){InstrumentationRegistry.getInstrumentation().sendStatus(0,Bundle().apply{putString("stream",message+"\n")})}
}
