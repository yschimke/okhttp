package okhttp3.internal.coroutines

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import okhttp3.Dispatcher

class OkHttpCoroutineDispatcher(val dispatcher: Dispatcher): CoroutineDispatcher() {
  override fun dispatch(context: CoroutineContext, block: Runnable) {
    println("Executing for ${context[CallContext.Key]?.call}")
    dispatcher.executorService.execute(block)
  }

  fun <T> runBlocking(block: suspend CoroutineScope.() -> T): T {

  }
}
