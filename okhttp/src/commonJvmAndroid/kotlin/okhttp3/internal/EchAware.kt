/*
 * Copyright (c) 2026 OkHttp Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:OptIn(okhttp3.ExperimentalOkHttpApi::class)

package okhttp3.internal

import okhttp3.Dns
import okhttp3.ech.EchConfig

/**
 * A [Dns] implementation that can also return HTTPS or SVCB ECH configuration for a host.
 *
 * This lives in the `internal` package because Java ignores Kotlin `internal` visibility; the
 * `okhttp3.internal` package signals that this is not a stable public API.
 */
internal interface EchAware {
  /**
   * Returns ECH configuration for [host], or null if no configuration is available.
   *
   * The returned [EchConfig] type is platform-specific. On Android this wraps an `EchConfigList`
   * suitable for configuring the TLS socket.
   */
  fun getEchConfig(host: String): EchConfig?
}
