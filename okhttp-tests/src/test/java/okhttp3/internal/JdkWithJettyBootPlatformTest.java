package okhttp3.internal;

import org.junit.Test;

import static okhttp3.internal.PlatformTest.getPlatform;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

public class JdkWithJettyBootPlatformTest {
  @Test
  public void testBuildsWithJettyBoot() {
    assumeTrue(getPlatform().equals("jdk-with-jetty-boot"));

    assertNotNull(JdkWithJettyBootPlatform.buildIfSupported());
  }
}
