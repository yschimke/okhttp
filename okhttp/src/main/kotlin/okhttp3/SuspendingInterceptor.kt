package okhttp3

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor.Chain

abstract class SuspendingInterceptor : Interceptor {
    final override fun intercept(chain: Interceptor.Chain): Response {
        return runBlocking {
            interceptAsync(chain)
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
        inline operator fun invoke(crossinline block: suspend (chain: Chain) -> Response): SuspendingInterceptor =
            object : SuspendingInterceptor() {
                override suspend fun interceptAsync(chain: Chain): Response {
                    return block(chain)
                }
            }
    }
}
