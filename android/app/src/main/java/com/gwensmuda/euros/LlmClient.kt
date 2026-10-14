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
                    "activities" -> inWeek(PlanDomain.day(record)) || payload.optString("completed_at")
                        .takeIf { it.isNotBlank() }?.let {
                            inWeek(java.time.OffsetDateTime.parse(it).atZoneSameInstant(PlanDomain.zone).toLocalDate())
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
