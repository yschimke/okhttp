package okhttp3

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.internal.connection.RealCall
import okhttp3.internal.coroutines.callCoroutineContext
import okhttp3.internal.coroutines.runBlocking
import kotlin.coroutines.coroutineContext

abstract class SuspendingInterceptor : Interceptor {
  final override fun intercept(chain: Interceptor.Chain): Response {
    return chain.call().runBlocking {
      try {
        interceptAsync(AsyncChain(chain))
      } catch (ibce: InvalidBlockingCall) {
        throw CancellationException(ibce)
      }
    }
  }

  suspend fun Interceptor.Chain.proceedAsync(request: Request): Response {
    // TODO unwrap
    return this.proceed(request)
  }

  abstract suspend fun interceptAsync(chain: Interceptor.Chain): Response

  companion object {
    inline operator fun invoke(
      crossinline block: suspend SuspendingInterceptor.(Interceptor.Chain) -> Response
    ): SuspendingInterceptor = object : SuspendingInterceptor() {
      override suspend fun interceptAsync(chain: Interceptor.Chain): Response {
        return block(chain)
      }
    }
  }
}

private class AsyncChain(val delegate: Interceptor.Chain): Interceptor.Chain by delegate {
  override fun proceed(request: Request): Response {
    // TODO block here
//    throw InvalidBlockingCall()

    return delegate.proceed(request)
  }
}

private class InvalidBlockingCall: IllegalStateException("Synchronous calls not allowed from SuspendingInterceptor")
