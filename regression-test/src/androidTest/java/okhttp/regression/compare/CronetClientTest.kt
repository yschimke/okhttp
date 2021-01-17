package okhttp.regression.compare

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.net.CronetProviderInstaller
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch

@RunWith(AndroidJUnit4::class)
class CronetClientTest {
  @Test
  fun get() {
    val latch = CountDownLatch(1)

    CronetProviderInstaller.installProvider(InstrumentationRegistry.getInstrumentation().targetContext)
      .addOnCompleteListener { task ->
        if (task.isSuccessful) {
          // Setup your code to use cronet here.
        }
        latch.countDown()
      }

    latch.await()

    val client = OkHttpClient()
    val request = Request.Builder()
      .url("https://google.com/robots.txt")
      .build()
    client.newCall(request).execute().use { response ->
      Assert.assertEquals(200, response.code().toLong())
      Assert.assertEquals(Protocol.HTTP_2, response.protocol())
    }
  }
}