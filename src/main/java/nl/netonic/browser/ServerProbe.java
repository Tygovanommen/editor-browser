package nl.netonic.browser;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * Whether a server accepts connections yet: for a tab whose local server refused the connection, which reloads when
 * it is up ({@code BrowserEditor}'s waiting for the server). Connects only while the server is down, so a server
 * that logs every connection ({@code php -S}) logs one line when it has come up. Blocking: off the UI thread only.
 */
final class ServerProbe {
  private static final int TIMEOUT_MS = 400;

  private ServerProbe() {}

  /** True when {@code host:port} accepts a TCP connection on any of its addresses (localhost: IPv4 and IPv6). */
  static boolean accepts(String host, int port) {
    InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host);
    } catch (IOException e) {
      return false;
    }
    for (InetAddress address : addresses) {
      try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress(address, port), TIMEOUT_MS);
        return true;
      } catch (IOException e) {
        // not on this address; try the next one (Vite on Node listens on ::1 only, for example)
      }
    }
    return false;
  }
}
