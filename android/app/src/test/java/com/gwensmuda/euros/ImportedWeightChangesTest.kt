package com.gwensmuda.euros

import org.junit.Assert.*
import org.junit.Test

class ImportedWeightChangesTest {
 @Test fun serverRoundTripDoesNotRepublishUnchangedScaleReading() {
  val local="""{"weight_lb":"155.000","source_weight_lb":155.0,"auto_imported":true,"original_kg":70.30681735,"source_key":"scale:a"}"""
  val server="""{"auto_imported": true, "original_kg": 70.30681735, "source_key": "scale:a", "source_weight_lb": 155, "weight_lb": "155.000"}"""
  assertTrue(sameImportedWeightPayload(server,local))
  assertFalse(sameImportedWeightPayload(server,local.replace("155.000","155.100")))
  assertFalse(sameImportedWeightPayload(server,local.replace("scale:a","scale:b")))
  assertFalse(sameImportedWeightPayload(server,local.replace("70.30681735","70.30681736")))
 }
}
