/*
 * Copyright (C) 2026 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package okhttp3.quiche4j

import okhttp3.Call
import okhttp3.EventListener

/**
 * Push-based `Call.cancel()` notification. Avoids the 500ms polling loop that
 * cronet-transport-for-okhttp uses.
 *
 * `Call.addEventListener(...)` (OkHttp 5+) composes an additional listener onto the call without
 * disturbing whatever listener the caller installed on the `OkHttpClient`. We register one that
 * reacts to [EventListener.canceled]: as soon as `RealCall.cancel()` fires it, we get a
 * synchronous callback and can tear down the QUIC stream via [io.quiche4j.Connection.streamShutdown].
 */
internal object CancellationHook {
  /**
   * Registers [onCancel] to fire when [call] is cancelled. Safe to call multiple times per
   * call; each [onCancel] fires at most once, on the thread that invokes `Call.cancel()`
   * — *or* synchronously on the current thread if the call was already cancelled before
   * this method was invoked.
   *
   * The synchronous path covers the race where the caller cancels `Call` between the
   * interceptor reading `chain.request()` and reaching this method: in that window the
   * event listeners on `call` have already been notified, so a newly-added listener
   * would never see `canceled()`. Without the post-register `isCanceled()` check the
   * hook would silently no-op and the QUIC handshake would proceed for a call the user
   * had asked us to drop.
   */
  fun attach(
    call: Call,
    onCancel: () -> Unit,
  ) {
    val fired = java.util.concurrent.atomic.AtomicBoolean(false)
    call.addEventListener(
      object : EventListener() {
        override fun canceled(call: Call) {
          if (fired.compareAndSet(false, true)) onCancel()
        }
      },
    )
    // Catch the "cancelled before we attached (or concurrently)" case. `fired` makes
    // double-invocation safe if the listener also wins the race.
    if (call.isCanceled() && fired.compareAndSet(false, true)) onCancel()
  }
}
