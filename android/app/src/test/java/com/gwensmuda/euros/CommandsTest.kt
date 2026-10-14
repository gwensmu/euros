package com.gwensmuda.euros
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
class CommandsTest {
 @Test fun completionPreservesSourceAndSchedule() {
  val raw="""{"title":"Class","kind":"bjj","scheduled_at":"2026-10-14T19:00:00-05:00","status":"pending","source":{"id":"tracker-1"}}"""
  val done=JSONObject(Commands.complete(Record("activities","one",4,raw),"done"))
  assertEquals("tracker-1",done.getJSONObject("source").getString("id"));assertEquals("done",done.getString("status"));assertEquals("2026-10-14T19:00:00-05:00",done.getString("scheduled_at"))
 }
 @Test fun retryKeepsCommandIdAndRevision() {
  val p=Pending("idempotent","activities","activity",7,"""{"status":"skipped"}""")
  assertEquals(Commands.envelope(p).toString(),Commands.envelope(p).toString())
  assertEquals(7,Commands.envelope(p).getInt("expected_revision"))
 }
 @Test(expected=IllegalArgumentException::class) fun noImplicitMissedState() { Commands.complete(Record("activities","id",1,"{}"),"missed") }
 @Test fun tombstonePreserved(){assertTrue(Commands.record(JSONObject("""{"entity_type":"activities","entity_id":"x","revision":3,"deleted":true,"payload":{}}""")).deleted)}
}
