package okhttp3.internal

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import okhttp3.Dispatcher

class OkHttpDispatcher(val dispatcher: Dispatcher): CoroutineDispatcher() {
  override fun dispatch(context: CoroutineContext, block: Runnable) {
    dispatcher
  }
}
