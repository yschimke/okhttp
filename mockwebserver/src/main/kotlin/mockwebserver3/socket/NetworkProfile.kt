/*
 * Copyright (C) 2026 Square, Inc.
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
package mockwebserver3.socket

import java.util.concurrent.TimeUnit

/**
 * Configuration for network simulation.
 *
 * @property latencyNanos Transmission delay in nanoseconds.
 * @property bytesPerSecond Bandwidth limit in bytes per second. 0 means unlimited.
 * @property maxWriteBufferSize Maximum bytes that can be buffered for transmission before blocking.
 */
public data class NetworkProfile(
        public val latencyNanos: Long = 0,
        public val bytesPerSecond: Long = 0,
        public val maxWriteBufferSize: Long = 65536,
) {
    public companion object {
        @JvmField
        public val LOCALHOST: NetworkProfile =
                NetworkProfile(
                        latencyNanos = TimeUnit.MILLISECONDS.toNanos(1),
                        bytesPerSecond = 1_000_000_000 // 1 GB/s
                )

        @JvmField
        public val SLOW_MOBILE: NetworkProfile =
                NetworkProfile(
                        latencyNanos = TimeUnit.MILLISECONDS.toNanos(200),
                        bytesPerSecond = 100_000 // 100 KB/s
                )
    }
}
