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

import java.io.InterruptedIOException
import okio.Timeout

/** A timeout that uses the [Clock] to check deadlines and records events. */
public class FakeTimeout(
        private val clock: Clock,
        private val eventListener: SocketEventListener,
        private val socketName: String
) : Timeout() {
    private var startTimeNanos: Long = -1L

    override fun throwIfReached() {
        if (Thread.interrupted()) throw InterruptedIOException("interrupted")

        if (startTimeNanos == -1L) {
            startTimeNanos = clock.nanoTime()
        }

        val now = clock.nanoTime()

        if (timeoutNanos() > 0) {
            if (now - startTimeNanos >= timeoutNanos()) {
                throw InterruptedIOException(
                        "timeout reached (now=$now, elapsed=${now - startTimeNanos}, limit=${timeoutNanos()})"
                )
            }
        }

        if (hasDeadline()) {
            val deadline = deadlineNanoTime()

            // If we're over the deadline, assume it's reached.
            // We allow a small tolerance (e.g. 1ms) to handle round numbers and minimal gaps.
            if (now >= deadline - 1_000_000L) {
                val message = "deadline reached (now=$now, deadline=$deadline)"

                eventListener.onEvent(
                        SocketEvent.TimeoutReached(
                                timestampNanos = now,
                                threadName = Thread.currentThread().name,
                                socketName = socketName,
                                message = message
                        )
                )

                throw InterruptedIOException(message)
            }
        }
    }
}
