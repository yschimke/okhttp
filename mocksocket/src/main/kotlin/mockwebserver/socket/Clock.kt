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
package mockwebserver.socket

import kotlinx.coroutines.delay

/**
 * A clock to control time in tests.
 *
 * Clocks provide two main capabilities:
 * 1. Tracking current time with [nanoTime].
 * 2. Delaying operations with [await].
 *
 * In [SYSTEM] and [AutoClock] modes, [await] advances time (real or simulated) as needed. In a
 * manual [FakeClock] mode, [await] blocks until the clock is advanced externally, allowing for
 * fine-grained control over test timing.
 */
public interface Clock {
    /** Returns the current time in nanoseconds. */
    public fun nanoTime(): Long

    /** Waits for [timeoutNanos] to elapse on this clock. */
    public suspend fun await(timeoutNanos: Long): Unit

    /** Returns a flow that emits whenever the clock advances or state changes. */
    public fun monitor(): kotlinx.coroutines.flow.Flow<Unit>

    /** Returns [nanoTime] in nanoseconds. */
    public val now: Long
        get() = nanoTime()

    /** Returns a [Timeout] that uses this clock to track time and deadlines. */
    public fun newTimeout(
            eventListener: SocketEventListener,
            socketName: String
    ): okio.Timeout = okio.Timeout()

    public companion object {
        @JvmField
        public val SYSTEM: Clock =
                object : Clock {
                    override fun nanoTime(): Long = System.nanoTime()

                    override suspend fun await(timeoutNanos: Long): Unit {
                        delay(timeoutNanos / 1_000_000)
                    }

                    override fun monitor(): kotlinx.coroutines.flow.Flow<Unit> =
                            kotlinx.coroutines.flow.flow {
                                while (true) {
                                    emit(Unit)
                                    delay(10)
                                }
                            }
                }
    }
}
