package com.gwensmuda.euros

import org.json.JSONObject

// Shared command and accepted-record wire representation.
object Commands {
 fun complete(record:Record,status:String):String {
  require(record.type=="activities" && !record.deleted)
  require(status in setOf("done","skipped"))
  return JSONObject(record.payload).put("status",status).toString()
 }
 fun envelope(p:Pending)=JSONObject().put("command_id",p.commandId).put("entity_type",p.type).put("entity_id",p.id).put("expected_revision",p.expectedRevision).put("operation",p.operation).put("payload",if(p.operation=="delete") JSONObject() else JSONObject(p.payload))
 fun record(json:JSONObject)=Record(json.getString("entity_type"),json.getString("entity_id"),json.getLong("revision"),json.getJSONObject("payload").toString(),json.optBoolean("deleted"))
}
