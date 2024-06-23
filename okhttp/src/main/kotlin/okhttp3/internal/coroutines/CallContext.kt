package okhttp3.internal.coroutines

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.Call
import okhttp3.internal.connection.RealCall

class CallContext(
  internal val call: RealCall
) : AbstractCoroutineContextElement(Key) {

  val dispatcher: CoroutineDispatcher
    get() = call.client.dispatcher.coroutineDispatcher

  companion object Key : CoroutineContext.Key<CallContext>
}

val Call.callCoroutineContext: CoroutineContext
  get() =
    // TODO drill down
    if (this is RealCall) {
      println(this)
      callContext.dispatcher + callContext
    } else {
      Dispatchers.IO
    }

fun <T> Call.runBlocking(block: suspend CoroutineScope.() -> T): T {
  val coroutineDispatcher = (this as RealCall).client.dispatcher.coroutineDispatcher
  return coroutineDispatcher.runBlocking {
    block()
  }
}

