package okhttp3

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking

abstract class SuspendingInterceptor : Interceptor {
  final override fun intercept(chain: Interceptor.Chain): Response {
    return runBlocking {
      try {
        interceptAsync(AsyncChain(chain))
      } catch (ibce: InvalidBlockingCall) {
        throw CancellationException(ibce)
      }
    }
  }

  abstract suspend fun interceptAsync(chain: Interceptor.Chain): Response

  companion object {
    /**
     * Constructs an interceptor for a lambda. This compact syntax is most useful for inline
     * interceptors.
     *
     * ```kotlin
     * val interceptor = SuspendingInterceptor { chain: Interceptor.Chain ->
     *     chain.proceedAsync(chain.request())
     * }
     * ```
     */
    inline operator fun invoke(
      crossinline block: suspend (chain: Interceptor.Chain) -> Response
    ): SuspendingInterceptor = object : SuspendingInterceptor() {
      override suspend fun interceptAsync(chain: Interceptor.Chain): Response {
        return block(chain)
      }
    }
  }
}

private class AsyncChain(delegate: Interceptor.Chain): Interceptor.Chain by delegate {
  override fun proceed(request: Request): Response {
    throw InvalidBlockingCall()
  }
}

private class InvalidBlockingCall: IllegalStateException("Synchronous calls not allowed from SuspendingInterceptor")
