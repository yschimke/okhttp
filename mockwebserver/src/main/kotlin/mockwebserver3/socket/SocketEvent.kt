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

public sealed class SocketEvent {
    public abstract val timestampNanos: Long
    public abstract val threadName: String
    public abstract val socketName: String

    public data class ReadSuccess(
            override val timestampNanos: Long,
            override val threadName: String,
            override val socketName: String,
            val byteCount: Long
    ) : SocketEvent()

    public data class ReadFailed(
            override val timestampNanos: Long,
            override val threadName: String,
            override val socketName: String,
            val reason: String
    ) : SocketEvent()

    public data class ReadWait(
            override val timestampNanos: Long,
            override val threadName: String,
            override val socketName: String,
            val waitNanos: Long
    ) : SocketEvent()

    public data class ReadEof(
            override val timestampNanos: Long,
            override val threadName: String,
            override val socketName: String
    ) : SocketEvent()

    public data class WriteSuccess(
            override val timestampNanos: Long,
            override val threadName: String,
            override val socketName: String,
            val byteCount: Long,
            val arrivalTimeNanos: Long
    ) : SocketEvent()

    public data class WriteFailed(
            override val timestampNanos: Long,
            override val threadName: String,
            override val socketName: String,
            val reason: String
    ) : SocketEvent()

    public data class WriteWaitBufferFull(
            override val timestampNanos: Long,
            override val threadName: String,
            override val socketName: String,
            val bufferSize: Long
    ) : SocketEvent()

    public data class Close(
            override val timestampNanos: Long,
            override val threadName: String,
            override val socketName: String
    ) : SocketEvent()

    public data class ShutdownInput(
            override val timestampNanos: Long,
            override val threadName: String,
            override val socketName: String
    ) : SocketEvent()

    public data class ShutdownOutput(
            override val timestampNanos: Long,
            override val threadName: String,
            override val socketName: String
    ) : SocketEvent()
}
