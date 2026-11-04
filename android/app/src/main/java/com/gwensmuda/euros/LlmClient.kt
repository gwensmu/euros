package com.gwensmuda.euros

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

/** Requests proposals only. Saving always uses the normal repository/outbox path. */
interface LlmClient {
    suspend fun interpretFood(text: String): JSONObject
    suspend fun reviewWeek(weekStart: LocalDate, records: List<Record>): JSONObject
    suspend fun reviewWeekJob(requestId:String,weekStart:LocalDate,records:List<Record>):JSONObject = reviewWeek(weekStart,records)
}

class BackendLlmClient(private val api: SyncApi) : LlmClient {
    override suspend fun interpretFood(text: String): JSONObject = withContext(Dispatchers.IO) {
        require(text.isNotBlank() && text.length <= 8000) { "Enter what you ate" }
        api.request("/v1/interpret/food", JSONObject().put("text", text))
    }

    override suspend fun reviewWeek(weekStart: LocalDate, records: List<Record>): JSONObject =
        withContext(Dispatchers.IO) {
            require(weekStart.dayOfWeek == DayOfWeek.MONDAY) { "Choose a week starting Monday" }
            val snapshot = JSONArray()
            weeklySnapshot(weekStart, records).forEach { record ->
                snapshot.put(JSONObject()
                    .put("entity_type", record.type)
                    .put("entity_id", record.id)
                    .put("revision", record.revision)
                    .put("deleted", record.deleted)
                    .put("payload", JSONObject(record.payload)))
            }
            api.request("/v1/review/week", JSONObject()
                .put("week_start", weekStart.toString()).put("records", snapshot))
        }

    override suspend fun reviewWeekJob(requestId:String,weekStart:LocalDate,records:List<Record>):JSONObject = withContext(Dispatchers.IO) {
        val snapshot=JSONArray()
        weeklySnapshot(weekStart,records).forEach{r->snapshot.put(JSONObject().put("entity_type",r.type).put("entity_id",r.id).put("revision",r.revision).put("deleted",r.deleted).put("payload",JSONObject(r.payload)))}
        var job=api.request("/v1/review/jobs",JSONObject().put("request_id",requestId).put("week_start",weekStart.toString()).put("records",snapshot))
        val id=job.getString("job_id")
        require(id==requestId && job.getString("week_start")==weekStart.toString())
        val started=System.nanoTime()
        while(true) {
            require(job.getString("job_id")==id && job.getString("week_start")==weekStart.toString())
            when(job.getString("status")) {
                "completed" -> return@withContext job.getJSONObject("result")
                "failed" -> throw IllegalStateException(backendErrorMessage(502,JSONObject().put("error",job.optString("error")).toString()))
                "queued","running" -> Unit
                else -> error("Invalid wrap-up status")
            }
            if((System.nanoTime()-started)/1_000_000_000>210)throw java.io.IOException("Wrap-up status timed out")
            kotlinx.coroutines.delay(2000)
            job=api.request("/v1/review/jobs/$id")
        }
        @Suppress("UNREACHABLE_CODE") JSONObject()
    }

    companion object {
        /** Raw Health Connect metadata and unrelated weeks never enter the request. */
        fun weeklySnapshot(week: LocalDate, records: List<Record>): List<Record> {
            val latest = records.groupBy { it.type to it.id }.values.map { versions ->
                versions.maxWith(compareBy<Record> { it.revision }.thenBy { it.deleted })
            }.filterNot { it.deleted }
            fun inWeek(day:LocalDate)=day >= week && day < week.plusWeeks(1)
            val weekRecords = latest.filter { record ->
                val payload=JSONObject(record.payload)
                when (record.type) {
                    "activities" -> inWeek(PlanDomain.day(record,PlanDomain.canonicalZone)) || payload.optString("completed_at")
                        .takeIf { it.isNotBlank() }?.let {
                            inWeek(java.time.OffsetDateTime.parse(it).atZoneSameInstant(PlanDomain.canonicalZone).toLocalDate())
                        } == true
                    "food_entries", "weight_entries" -> inWeek(LocalDate.parse(payload.getString("local_date")))
                    else -> false
                }
            }.toMutableList()
            // Include recovery family context even when a step crosses a week boundary.
            val activityIds=weekRecords.filter{it.type=="activities"}.map{it.id}.toMutableSet()
            var expanded=true
            while(expanded) {
                expanded=false
                latest.filter{it.type=="activities"}.forEach { activity ->
                    val parent=JSONObject(activity.payload).optString("parent_id")
                    if(parent.isNotBlank() && (activity.id in activityIds || parent in activityIds)) {
                        if(activityIds.add(parent))expanded=true
                        if(activityIds.add(activity.id))expanded=true
                    }
                }
            }
            weekRecords.addAll(latest.filter{it.type=="activities" && it.id in activityIds})
            val priorWeights = latest.filter { record ->
                record.type == "weight_entries" && JSONObject(record.payload)
                    .optString("weekly_anchor").let { it.isNotBlank() && it < week.toString() }
            }.sortedBy { JSONObject(it.payload).getString("weekly_anchor") }.takeLast(2)
            return (weekRecords + priorWeights).distinctBy { it.type to it.id }.sortedWith(
                compareBy<Record> { it.type }.thenBy { it.id })
        }
    }
}
