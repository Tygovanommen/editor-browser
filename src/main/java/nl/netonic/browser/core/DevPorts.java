package nl.netonic.browser.core;

import java.util.Set;
import java.util.TreeSet;

/**
 * Spotting a dev server started outside a run configuration (e.g. {@code npm run dev} in the Terminal) by the
 * well-known dev-server ports that start listening. Ports are read from the OS's list of listening sockets
 * ({@code /proc/net/tcp}, {@code netstat}), never by connecting to them: {@code php -S} logs every connection.
 * Pure JDK, see CoreTests.
 */
public final class DevPorts {
  /**
   * Default ports of common dev servers: Hugo 1313, Next / Nuxt / Remix / Express 3000(+1), Adonis 3333,
   * Jekyll / Phoenix 4000, Vite preview 4173, Angular 4200, Astro 4321, Vite 5173(+1, next free), Storybook 6006,
   * {@code php -S} / {@code artisan serve} / Django 8000, webpack / Vue CLI 8080(+1). Not 5000 / 7000 (AirPlay
   * on macOS) or 9000 (php-fpm, not HTTP).
   */
  public static final Set<Integer> WATCHED = Set.of(1313, 3000, 3001, 3333, 4000, 4173, 4200, 4321, 5173, 5174,
      6006, 8000, 8080, 8081);

  private DevPorts() {}

  /** Listening TCP ports in BSD / macOS {@code netstat -an -p tcp} output ({@code tcp4 0 0 127.0.0.1.5173 *.* LISTEN}). */
  public static Set<Integer> parseBsdNetstat(String output) {
    Set<Integer> ports = new TreeSet<>();
    if (output == null) return ports;
    for (String line : output.split("\n")) {
      String[] t = line.trim().split("\\s+");
      if (t.length < 6 || !t[0].startsWith("tcp") || !t[t.length - 1].equals("LISTEN")) continue;
      addPort(ports, t[3].substring(t[3].lastIndexOf('.') + 1));
    }
    return ports;
  }

  /**
   * Listening TCP ports in Windows {@code netstat -an} output ({@code TCP 127.0.0.1:5173 0.0.0.0:0 LISTENING}).
   * Recognised by the empty foreign address, as the state column is translated on non-English Windows.
   */
  public static Set<Integer> parseWindowsNetstat(String output) {
    Set<Integer> ports = new TreeSet<>();
    if (output == null) return ports;
    for (String line : output.split("\n")) {
      String[] t = line.trim().split("\\s+");
      if (t.length < 3 || !t[0].equals("TCP")) continue;
      if (!t[2].equals("0.0.0.0:0") && !t[2].equals("[::]:0")) continue;
      addPort(ports, t[1].substring(t[1].lastIndexOf(':') + 1));
    }
    return ports;
  }

  /** Listening ports in Linux {@code /proc/net/tcp} or {@code tcp6} ({@code 0: 0100007F:1435 00000000:0000 0A …}). */
  public static Set<Integer> parseProcNetTcp(String content) {
    Set<Integer> ports = new TreeSet<>();
    if (content == null) return ports;
    for (String line : content.split("\n")) {
      String[] t = line.trim().split("\\s+");
      if (t.length < 4 || !t[3].equals("0A")) continue; // 0A = TCP_LISTEN
      int colon = t[1].lastIndexOf(':');
      if (colon < 0) continue;
      try {
        ports.add(Integer.parseInt(t[1].substring(colon + 1), 16));
      } catch (NumberFormatException ignored) {
        // header or malformed line
      }
    }
    return ports;
  }

  private static void addPort(Set<Integer> ports, String text) {
    try {
      int port = Integer.parseInt(text);
      if (port > 0 && port < 65536) ports.add(port);
    } catch (NumberFormatException ignored) {
      // "*" or a header
    }
  }

  /**
   * Remembers which watched ports were listening at the previous look and reports the ones that started since.
   * The first look only sets the baseline, so servers already running when the IDE starts (or when the setting
   * is switched on) don't open tabs. Not thread-safe: use from one thread.
   */
  public static final class Watch {
    private Set<Integer> previous;

    /** The watched ports in {@code listening} that weren't listening at the previous call; empty on the first. */
    public Set<Integer> update(Set<Integer> listening) {
      Set<Integer> now = new TreeSet<>(listening);
      now.retainAll(WATCHED);
      Set<Integer> started = new TreeSet<>(now);
      if (previous == null) started.clear();
      else started.removeAll(previous);
      previous = now;
      return started;
    }

    /** Forget the baseline, e.g. while the feature is switched off. */
    public void reset() {
      previous = null;
    }
  }
}
