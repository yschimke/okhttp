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

/**
 * A clock that automatically advances by the appropriate amount of time given the client/server IO
 * interactions and timeouts.
 */
public class AutoClock : FakeClock() {
    override fun await(condition: Condition, timeoutNanos: Long) {
        if (timeoutNanos <= 0) return

        // Advance simulated time by the full requested timeout duration.
        // MockSocket.read/write calculates this based on the next scheduled event or timeouts.
        if (timeoutNanos < Long.MAX_VALUE) {
            advanceBy(timeoutNanos)
        }

        // Yield execution briefly to allow other threads to run if they are ready to signal.
        // This is safe because if they signal, MockSocket would wake up anyway.
        // If they don't signal, MockSocket will check time and see timeout/event.
        condition.await(1, TimeUnit.MILLISECONDS)
    }
}
