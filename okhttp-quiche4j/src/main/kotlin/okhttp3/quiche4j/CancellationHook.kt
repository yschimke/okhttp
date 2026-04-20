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
   * Registers [onCancel] to fire when [call] is cancelled. Safe to call multiple times per call;
   * each [onCancel] fires at most once, on the thread that invokes `Call.cancel()`.
   */
  fun attach(
    call: Call,
    onCancel: () -> Unit,
  ) {
    call.addEventListener(
      object : EventListener() {
        private val fired = java.util.concurrent.atomic.AtomicBoolean(false)

        override fun canceled(call: Call) {
          if (fired.compareAndSet(false, true)) onCancel()
        }
      },
    )
  }
}
