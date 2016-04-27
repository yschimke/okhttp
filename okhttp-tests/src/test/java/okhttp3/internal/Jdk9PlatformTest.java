package okhttp3.internal;

import org.junit.Test;

import static okhttp3.internal.PlatformTest.getPlatform;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

public class Jdk9PlatformTest {
  @Test
  public void testBuildsWhenJdk9() {
    assumeTrue(getPlatform().equals("jdk9"));

    assertNotNull(Jdk9Platform.buildIfSupported());
  }

  @Test
  public void findsAlpnMethods() {
    assumeTrue(getPlatform().equals("jdk9"));

    Jdk9Platform platform = Jdk9Platform.buildIfSupported();

    assertEquals("getApplicationProtocol", platform.getProtocolMethod.getName());
    assertEquals("setApplicationProtocols", platform.setProtocolMethod.getName());
  }
}
