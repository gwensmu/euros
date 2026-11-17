package com.gwensmuda.euros

import kotlinx.coroutines.delay

/** Visible screens must not wait behind a WorkManager retry chain. The connection's gate
 * still serializes this with background sync and manual sync. Lifecycle cancellation stops
 * polling; durable background work remains responsible for offline writes. */
internal suspend fun SyncConnection.syncWhileVisible(pause:suspend ()->Unit={delay(60_000)}) {
 initialize()
 while(true) {
  if(state.value.connected) sync()
  pause()
 }
}
