package com.gwensmuda.euros

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class)
class MealDismissalsTest {
 private val day=LocalDate.parse("2026-10-15")
 private val meals=listOf("breakfast","lunch").map{FoodMealReference(it,it,null,null,null,null)}
 @Test fun dismissUndoAndRestoreAreDateScopedAndDoNotWriteDomainRecords()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>()
  val db=Room.inMemoryDatabaseBuilder(context,EurosDatabase::class.java).build()
  try {
   val repo=Repository(db)
   repo.setMealDismissed(day,"breakfast",true)
   repo.setMealDismissed(day,"lunch",true)
   repo.setMealDismissed(day.plusDays(1),"breakfast",true)
   assertTrue(FoodDomain.remainingReferences(meals,emptyList(),day,repo.observeDismissedMeals(day).first()).isEmpty())
   repo.setMealDismissed(day,"breakfast",false)
   assertEquals(listOf("breakfast"),FoodDomain.remainingReferences(meals,emptyList(),day,repo.observeDismissedMeals(day).first()).map{it.id})
   repo.restoreDismissedMeals(day)
   assertEquals(emptySet<String>(),repo.observeDismissedMeals(day).first())
   assertEquals(setOf("breakfast"),repo.observeDismissedMeals(day.plusDays(1)).first())
   assertTrue(repo.dao.recordsIncludingDeleted().isEmpty())
   assertTrue(repo.dao.pending().isEmpty())
  } finally {db.close()}
 }
 @Test fun dismissalSurvivesReopenAndDoesNotHideTomorrow()=runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>()
  val name="meal-dismissal-test.db"
  context.deleteDatabase(name)
  var db=Room.databaseBuilder(context,EurosDatabase::class.java,name).build()
  try {
   Repository(db).setMealDismissed(day,"breakfast",true)
   db.close()
   db=Room.databaseBuilder(context,EurosDatabase::class.java,name).build()
   val repo=Repository(db)
   assertEquals(setOf("breakfast"),repo.observeDismissedMeals(day).first())
   assertEquals(listOf("lunch"),FoodDomain.remainingReferences(meals,emptyList(),day,repo.observeDismissedMeals(day).first()).map{it.id})
   assertEquals(meals,FoodDomain.remainingReferences(meals,emptyList(),day.plusDays(1),repo.observeDismissedMeals(day.plusDays(1)).first()))
  } finally {db.close();context.deleteDatabase(name)}
 }
}
