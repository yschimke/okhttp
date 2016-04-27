package okhttp3.internal;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import okhttp3.Protocol;

/**
 * OpenJDK 9+.
 */
final class Jdk9Platform extends Platform {
  final Method setProtocolMethod;
  final Method getProtocolMethod;

  public Jdk9Platform(Method setProtocolMethod, Method getProtocolMethod) {
    this.setProtocolMethod = setProtocolMethod;
    this.getProtocolMethod = getProtocolMethod;
  }

  @Override
  public void configureTlsExtensions(SSLSocket sslSocket, String hostname,
                                     List<Protocol> protocols) {
    try {
      SSLParameters sslParameters = sslSocket.getSSLParameters();

      List<String> names = alpnProtocolNames(protocols);

      setProtocolMethod.invoke(sslParameters,
          new Object[]{names.toArray(new String[names.size()])});

      sslSocket.setSSLParameters(sslParameters);
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new AssertionError();
    }
  }

  @Override
  public String getSelectedProtocol(SSLSocket socket) {
    try {
      String protocol = (String) getProtocolMethod.invoke(socket);

      // SSLSocket.getApplicationProtocol returns "" if application protocols values will not
      // be used. Observed if you didn't specify SSLParameters.setApplicationProtocols
      if (protocol == null || protocol.equals("")) {
        return null;
      }

      return protocol;
    } catch (IllegalAccessException | InvocationTargetException e) {
      throw new AssertionError();
    }
  }

  public static Jdk9Platform buildIfSupported() {
    // Find JDK 9 new methods
    try {
      Method setProtocolMethod =
          SSLParameters.class.getMethod("setApplicationProtocols", String[].class);
      Method getProtocolMethod = SSLSocket.class.getMethod("getApplicationProtocol");

      return new Jdk9Platform(setProtocolMethod, getProtocolMethod);
    } catch (NoSuchMethodException ignored) {
      // pre JDK 9
    }

    return null;
  }
}
