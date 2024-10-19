package okhttp.android.test

import assertk.assertThat
import assertk.assertions.isNotNull
import okhttp3.internal.ws.MessageInflater
import okio.Buffer
import okio.ByteString
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.jupiter.api.Test

class InflateTest {
  /**
   * Illegal argument exception: failed requirements after bytes left over.
   *
   * https://github.com/square/okhttp/issues/8551
   */
  @Test fun illegalargumentexceptionafterbadpacket() {
    val inflater = MessageInflater(true)

    val message = FileSystem.RESOURCES.read("issue8551.txt".toPath()) {
      readByteString()
    }
    println(message)
    assertThat(inflater.inflate(message)).isNotNull()
  }

  private fun MessageInflater.inflate(byteString: ByteString): ByteString {
    val buffer = Buffer()
    buffer.write(byteString)
    inflate(buffer)
    return buffer.readByteString()
  }
}
