package com.gwensmuda.euros

import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LaunchDestinationTest {
 @Test fun notificationDestinationIsConsumedOnce() {
  val intent=Intent().putExtra("activity_id","@weight").setData(Uri.parse("euros://activity/weight"))
  assertEquals("@weight",consumeLaunchDestination(intent))
  assertFalse(intent.hasExtra("activity_id"));assertNull(intent.data)
  assertNull(consumeLaunchDestination(intent))
 }
 @Test fun launcherReturnsHomeAndNewNotificationStillOpensItsDestination() {
  val launcher=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
  assertEquals("@today",consumeLaunchDestination(launcher))
  assertEquals("@weight",consumeLaunchDestination(Intent().putExtra("activity_id","@weight")))
  assertEquals("@today",consumeLaunchDestination(launcher))
 }
}
