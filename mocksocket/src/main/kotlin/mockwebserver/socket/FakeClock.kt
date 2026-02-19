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

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A clock that can be manually advanced for testing. */
public open class FakeClock : Clock {
    private val _nanoTime: AtomicLong = AtomicLong(0)
    private val mutex: Mutex = Mutex()
    public var onWait: ((Long) -> Unit)? = null
    public val timeChanged: MutableSharedFlow<Unit> =
            MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)

    override fun nanoTime(): Long = _nanoTime.get()

    /** Advances the clock by [nanos] and notifies any waiting threads. */
    public suspend fun advanceBy(nanos: Long, unit: TimeUnit): Unit {
        mutex.withLock {
            _nanoTime.addAndGet(unit.toNanos(nanos))
            timeChanged.emit(Unit)
        }
    }

    /** Advances the clock by [nanos] and notifies any waiting threads. */
    public suspend fun advanceBy(nanos: Long): Unit {
        advanceBy(nanos, TimeUnit.NANOSECONDS)
    }

    /**
     * Waits until the clock has advanced by [nanos]. This does NOT advance the clock itself; it
     * just waits for someone else to advance it.
     */
    public suspend fun sleep(nanos: Long): Unit {
        onWait?.invoke(nanos)
        val target = _nanoTime.get() + nanos
        while (_nanoTime.get() < target) {
            timeChanged.first()
        }
    }

    override suspend fun await(timeoutNanos: Long): Unit {
        onWait?.invoke(timeoutNanos)
        val startNanoTime = _nanoTime.get()
        while (_nanoTime.get() - startNanoTime < timeoutNanos) {
            timeChanged.first()
        }
    }

    override fun monitor(): kotlinx.coroutines.flow.Flow<Unit> = timeChanged

    override fun newTimeout(
            eventListener: SocketEventListener,
            socketName: String
    ): okio.Timeout = FakeTimeout(this, eventListener, socketName)
}

/** Suspending API to monitor actions on a [FakeClock]. */
public suspend fun FakeClock.monitor(block: suspend () -> Unit) {
    coroutineScope { block() }
}
