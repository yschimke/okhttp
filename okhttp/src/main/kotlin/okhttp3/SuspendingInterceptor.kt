package okhttp3

import kotlinx.coroutines.runBlocking

abstract class SuspendingInterceptor: Interceptor {
  final override fun intercept(chain: Interceptor.Chain): Response {
    return runBlocking {
      interceptAsync(chain)
    }
  }

  abstract suspend fun interceptAsync(chain: Interceptor.Chain): Response
}
