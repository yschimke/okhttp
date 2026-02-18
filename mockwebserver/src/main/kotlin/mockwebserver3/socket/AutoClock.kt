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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/**
 * A clock that automatically advances time when nothing is happening. Use this for tests where the
 * exact timing of clock advancements doesn't matter, but you still want to test timeout
 * interactions and timeouts.
 */
public class AutoClock : FakeClock() {
    override suspend fun await(timeoutNanos: Long): Unit {
        if (timeoutNanos <= 0) return

        val startRealTime = System.nanoTime()
        val realLimit =
                TimeUnit.SECONDS.toNanos(1) // 1 second real-world timeout for deadlock detection

        var loopCount = 0
        while (true) {
            loopCount++
            yield()

            // Brief wait for external signal
            val signaled = withTimeoutOrNull(1) { timeChanged.first() }
            if (signaled != null) return

            val nowRealTime = System.nanoTime()
            if (nowRealTime - startRealTime >= realLimit) {
                throw IOException(
                        "Real-world timeout of 1s reached in AutoClock! Possible deadlock or starvation. " +
                                "(looped $loopCount times)"
                )
            }

            // Only if we weren't signaled after a brief real wait, we advance simulated time.
            if (timeoutNanos < Long.MAX_VALUE) {
                advanceBy(timeoutNanos)
                return
            }

            // If it's infinite wait, we just wait for timeChanged.
            timeChanged.first()
        }
    }
}
