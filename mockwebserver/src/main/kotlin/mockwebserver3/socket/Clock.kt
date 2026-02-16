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
import java.util.concurrent.locks.Condition

/** A clock to control time in tests. */
public interface Clock {
    /** Returns the current time in nanoseconds. */
    public fun nanoTime(): Long

    /**
     * Waits for [condition] to be signaled or for [timeoutNanos] to elapse.
     *
     * Returns true if the condition was signaled, or false if the timeout elapsed.
     */
    public fun await(condition: Condition, timeoutNanos: Long)

    /** Returns [nanoTime] in nanoseconds. */
    public val now: Long
        get() = nanoTime()

    /** Returns a [Timeout] that uses this clock to track time and deadlines. */
    public fun newTimeout(): okio.Timeout = okio.Timeout()

    public companion object {
        @JvmField
        public val SYSTEM: Clock =
                object : Clock {
                    override fun nanoTime(): Long = System.nanoTime()

                    override fun await(condition: Condition, timeoutNanos: Long) {
                        condition.await(timeoutNanos, TimeUnit.NANOSECONDS)
                    }
                }
    }
}
