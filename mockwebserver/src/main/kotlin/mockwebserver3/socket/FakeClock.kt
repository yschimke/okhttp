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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A clock that can be manually advanced for testing. */
public open class FakeClock : Clock {
    protected val lock: ReentrantLock = ReentrantLock()
    protected val condition: Condition = lock.newCondition()
    protected var nanoTime: Long = 0L

    override fun nanoTime(): Long = lock.withLock { nanoTime }

    /** Advances the clock by [nanos] and notifies any waiting threads. */
    public fun advanceBy(nanos: Long, unit: TimeUnit) {
        lock.withLock {
            nanoTime += unit.toNanos(nanos)
            condition.signalAll()
        }
    }

    /** Advances the clock by [nanos] and notifies any waiting threads. */
    public fun advanceBy(nanos: Long) {
        advanceBy(nanos, TimeUnit.NANOSECONDS)
    }

    /**
     * Waits until the clock has advanced by [nanos]. This does NOT advance the clock itself; it
     * just waits for someone else to advance it.
     */
    public fun sleep(nanos: Long) {
        lock.withLock {
            val target = nanoTime + nanos
            while (nanoTime < target) {
                condition.await()
            }
        }
    }

    override fun await(condition: Condition, timeoutNanos: Long) {
        val startNanoTime = lock.withLock { nanoTime }
        val startRealTime = System.nanoTime()
        val realLimit =
                TimeUnit.SECONDS.toNanos(1) // 1 second real-world timeout for deadlock detection
        var loopCount = 0
        while (true) {
            loopCount++
            if (loopCount % 1000 == 0) {
                println("FakeClock loop count: $loopCount")
            }
            val nowRealTime = System.nanoTime()
            if (nowRealTime - startRealTime >= realLimit) {
                throw IOException(
                        "Real-world timeout of 1s reached! Possible deadlock in FakeClock usage. " +
                                "(looped $loopCount times)"
                )
            }

            // Check if simulated time has already passed the required duration
            val nowNanoTime = lock.withLock { nanoTime }
            if (nowNanoTime - startNanoTime >= timeoutNanos) {
                return
            }

            // Wait for 10ms to see if the condition is signaled
            if (condition.await(10, TimeUnit.MILLISECONDS)) {
                return // Signaled!
            }
        }
    }

    override fun newTimeout(): okio.Timeout = okio.Timeout()
}
