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

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition

/**
 * A clock that automatically advances by the appropriate amount of time given the client/server IO
 * interactions and timeouts.
 */
public class AutoClock : FakeClock() {
    override fun await(condition: Condition, timeoutNanos: Long) {
        if (timeoutNanos <= 0) return

        val startRealTime = System.nanoTime()
        val realLimit =
                TimeUnit.SECONDS.toNanos(1) // 1 second real-world timeout for deadlock detection

        // Yield execution briefly on the real clock to allow other threads to run
        // without advancing simulated time. If someone signals us (e.g. they write
        // data), we don't need to advance time yet.
        var loopCount = 0
        while (true) {
            loopCount++
            if (loopCount % 1000 == 0) {
                println("AutoClock loop count: $loopCount")
            }
            if (condition.await(1, TimeUnit.MILLISECONDS)) return

            val nowRealTime = System.nanoTime()
            if (nowRealTime - startRealTime >= realLimit) {
                throw IOException(
                        "Real-world timeout of 1s reached in AutoClock! Possible deadlock or starvation. " +
                                "(looped $loopCount times)"
                )
            }

            // Only if we weren't signaled after a brief real wait, we advance simulated time.
            if (timeoutNanos < Long.MAX_VALUE) {
                println("AutoClock: advancing by $timeoutNanos nanos")
                advanceBy(timeoutNanos)
                return
            }
        }
    }
}
