package com.gwensmuda.euros

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Requests proposals only. Saving always uses the normal repository/outbox path. */
interface LlmClient {
    suspend fun interpretFood(text: String): JSONObject
}

class BackendLlmClient(private val api: SyncApi) : LlmClient {
    override suspend fun interpretFood(text: String): JSONObject = withContext(Dispatchers.IO) {
        require(text.isNotBlank() && text.length <= 8000) { "Enter what you ate" }
        api.request("/v1/interpret/food", JSONObject().put("text", text))
    }

}
