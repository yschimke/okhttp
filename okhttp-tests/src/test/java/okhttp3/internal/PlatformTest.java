package okhttp3.internal;

import org.junit.Test;

public class PlatformTest {
  @Test
  public void alwaysBuilds() {
    new Platform();
  }

  public static String getPlatform() {
    return System.getProperty("okhttp.platform", "platform");
  }
}
