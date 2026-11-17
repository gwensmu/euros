package com.gwensmuda.euros

import com.google.gson.JsonParser

// Server canonical JSON changes key order and whitespace, not the observed measurement.
internal fun sameImportedWeightPayload(saved:String,incoming:String):Boolean =
 JsonParser.parseString(saved)==JsonParser.parseString(incoming)
