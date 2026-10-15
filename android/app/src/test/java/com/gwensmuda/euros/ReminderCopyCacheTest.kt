package com.gwensmuda.euros
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class)
class ReminderCopyCacheTest {
 @Test fun deliveryReadsOnlyExactContextAndIgnoresOldVersion()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>()
  val db=Room.inMemoryDatabaseBuilder(context,EurosDatabase::class.java).build()
  try {
   val cache=ReminderCopyCache(context,db.dao())
   val plan=RichReminder("workout",Instant.parse("2026-10-15T22:30:00Z"),"Comp class","Comp class is at 6:30 PM.","workout","training")
   assertNull(cache.joke(plan))
   val payload=JSONObject().put("context_key",plan.contextKey).put("prompt_version","weasel-reminders-v1").put("joke","My gi is ready.")
   db.dao().meta(Metadata("reminder_copy:"+plan.contextKey,payload.toString()))
   assertEquals("My gi is ready.",cache.joke(plan))
   assertNull(cache.joke(plan.copy(facts="Class moved to 7:00 PM.")))
   assertNull(cache.joke(plan.copy(allowSkipJoke=true)))
   db.dao().meta(Metadata("reminder_copy:"+plan.contextKey,payload.put("prompt_version","old").toString()))
   assertNull(cache.joke(plan))
   assertTrue(db.dao().pending().isEmpty());assertTrue(db.dao().recordsIncludingDeleted().isEmpty())
  }finally{db.close()}
 }
}
