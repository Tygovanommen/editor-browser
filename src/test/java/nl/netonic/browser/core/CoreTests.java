package nl.netonic.browser.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Dependency-free tests for the core.
 * Run: java -cp build/core:build/test nl.netonic.browser.core.CoreTests
 */
public final class CoreTests {
  private static int passed;
  private static final List<String> failed = new ArrayList<>();

  public static void main(String[] args) {
    test("normalize: schemes are kept", CoreTests::normalizeKeepsSchemes);
    test("normalize: local hosts get http, others https", CoreTests::normalizeAddsScheme);
    test("looksLikeUrl: addresses yes, file names and text no", CoreTests::looksLikeUrl);
    test("label: host and port, www dropped", CoreTests::label);
    test("viewSource: view-source: address of a page", CoreTests::viewSource);
    test("shorten", CoreTests::shorten);
    test("findDevServerUrl: picks the local URL out of console output", CoreTests::findDevServerUrl);
    test("hostPort: same host:port across equivalent URLs", CoreTests::hostPort);
    test("DevPorts: listening ports from netstat / proc output", CoreTests::devPortsParse);
    test("DevPorts.Watch: reports watched ports that started since the last look", CoreTests::devPortsWatch);

    System.out.println();
    System.out.println(passed + " passed, " + failed.size() + " failed");
    failed.forEach(f -> System.out.println("  FAILED: " + f));
    if (!failed.isEmpty()) System.exit(1);
  }

  static void normalizeKeepsSchemes() {
    eq("https://example.com/a?b=1", Urls.normalize("https://example.com/a?b=1"));
    eq("http://backend.test/admin", Urls.normalize("  http://backend.test/admin  "));
    eq("about:blank", Urls.normalize("about:blank"));
    eq("file:///tmp/x.html", Urls.normalize("file:///tmp/x.html"));
    eq(null, Urls.normalize("   "));
    eq(null, Urls.normalize(null));
  }

  static void normalizeAddsScheme() {
    eq("http://localhost:3000", Urls.normalize("localhost:3000"));
    eq("http://localhost:3000/reserveren", Urls.normalize("localhost:3000/reserveren"));
    eq("http://backend.test/admin", Urls.normalize("backend.test/admin"));
    eq("http://127.0.0.1:8025", Urls.normalize("127.0.0.1:8025"));
    eq("http://app.localhost", Urls.normalize("app.localhost"));
    eq("http://printer.local", Urls.normalize("printer.local"));
    eq("https://example.com", Urls.normalize("example.com"));
    eq("https://github.com/x/y", Urls.normalize("//github.com/x/y"));
  }

  static void looksLikeUrl() {
    yes(Urls.looksLikeUrl("https://example.com"));
    yes(Urls.looksLikeUrl("localhost:3000"));
    yes(Urls.looksLikeUrl("backend.test/admin"));
    yes(Urls.looksLikeUrl("example.com"));
    yes(Urls.looksLikeUrl("127.0.0.1:8025"));
    no(Urls.looksLikeUrl("app.vue"));
    no(Urls.looksLikeUrl("composer.json"));
    no(Urls.looksLikeUrl("hello world.com"));
    no(Urls.looksLikeUrl("plainword"));
    no(Urls.looksLikeUrl("1.5"));
    no(Urls.looksLikeUrl("javascript:alert(1)"));
    no(Urls.looksLikeUrl(""));
  }

  static void label() {
    eq("localhost:3000", Urls.label("http://localhost:3000/reserveren"));
    eq("example.com", Urls.label("https://www.example.com/a"));
    eq("backend.test", Urls.label("http://backend.test/admin"));
    eq("New Tab", Urls.label("about:blank"));
    eq("New Tab", Urls.label(null));
    eq("view-source:localhost:3000", Urls.label("view-source:http://localhost:3000/reserveren"));
  }

  static void viewSource() {
    eq("view-source:http://localhost:3000/a?b=1", Urls.viewSource("http://localhost:3000/a?b=1"));
    eq("view-source:https://example.com/", Urls.viewSource(" https://example.com/ "));
    eq("view-source:https://example.com/", Urls.viewSource("view-source:https://example.com/"));
    eq("VIEW-SOURCE:https://example.com/", Urls.viewSource("VIEW-SOURCE:https://example.com/"));
    eq(null, Urls.viewSource("about:blank"));
    eq(null, Urls.viewSource(" "));
    eq(null, Urls.viewSource(null));
    yes(Urls.isViewSource("view-source:http://localhost:3000/"));
    yes(Urls.isViewSource("View-Source:http://localhost:3000/"));
    no(Urls.isViewSource("http://localhost:3000/view-source:x"));
    no(Urls.isViewSource(null));
  }

  static void shorten() {
    eq("abc", Urls.shorten(" abc ", 10));
    eq("abcdefghi…", Urls.shorten("abcdefghijklmnop", 10));
    eq("", Urls.shorten(null, 10));
  }

  static void findDevServerUrl() {
    eq("http://localhost:5173/", Urls.findDevServerUrl("  ➔  Local:   \u001B[1mhttp://localhost:5173/\u001B[22m\n"));
    eq("http://127.0.0.1:8080", Urls.findDevServerUrl("Project is running at: http://127.0.0.1:8080 (ready)."));
    eq("https://backend.test/admin", Urls.findDevServerUrl("serving https://backend.test/admin now"));
    eq(null, Urls.findDevServerUrl("Compiled successfully in 412ms"));
    eq(null, Urls.findDevServerUrl("see https://example.com for docs"));
    eq(null, Urls.findDevServerUrl(null));
  }

  static void hostPort() {
    eq("localhost:3000", Urls.hostPort("http://localhost:3000/reserveren"));
    eq("localhost:80", Urls.hostPort("http://localhost"));
    eq("example.com:443", Urls.hostPort("https://example.com/a"));
    eq(null, Urls.hostPort("about:blank"));
    eq("localhost:8000", Urls.hostPort("http://127.0.0.1:8000/"));
    eq("localhost:5173", Urls.hostPort("http://[::1]:5173/"));
    eq("localhost:5173", Urls.hostPort("http://LocalHost:5173"));
  }

  static void devPortsParse() {
    String mac = """
        Active Internet connections (including servers)
        Proto Recv-Q Send-Q  Local Address          Foreign Address        (state)
        tcp4       0      0  127.0.0.1.5173         *.*                    LISTEN
        tcp6       0      0  ::1.3000               *.*                    LISTEN
        tcp46      0      0  *.8000                 *.*                    LISTEN
        tcp4       0      0  192.168.1.5.50123      17.57.144.1.5223       ESTABLISHED
        tcp4       0      0  127.0.0.1.8080         127.0.0.1.61234        TIME_WAIT
        """;
    eq(Set.of(5173, 3000, 8000), DevPorts.parseBsdNetstat(mac));
    String win = """

        Active Connections

          Proto  Local Address          Foreign Address        State
          TCP    0.0.0.0:135            0.0.0.0:0              LISTENING
          TCP    127.0.0.1:5173         0.0.0.0:0              ABHÖREN
          TCP    127.0.0.1:50123        127.0.0.1:8080         ESTABLISHED
          TCP    [::1]:4200             [::]:0                 LISTENING
          UDP    0.0.0.0:5353           *:*
        """.replace("\n", "\r\n");
    eq(Set.of(135, 5173, 4200), DevPorts.parseWindowsNetstat(win));
    String proc = """
          sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
           0: 0100007F:1435 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 1
           1: 0100007F:1F90 0100007F:D3F2 01 00000000:00000000 00:00000000 00000000  1000        0 2
           0: 00000000000000000000000001000000:0BB8 00000000000000000000000000000000:0000 0A 0 0 0 0 0 0
        """;
    eq(Set.of(5173, 3000), DevPorts.parseProcNetTcp(proc));
    eq(Set.of(), DevPorts.parseBsdNetstat(null));
  }

  static void devPortsWatch() {
    DevPorts.Watch watch = new DevPorts.Watch();
    eq(Set.of(), watch.update(Set.of(5173, 22))); // baseline: already running, no tab
    eq(Set.of(), watch.update(Set.of(5173, 22)));
    eq(Set.of(3000), watch.update(Set.of(5173, 3000, 22, 61000))); // 61000 isn't a dev port
    eq(Set.of(), watch.update(Set.of(22)));
    eq(Set.of(5173), watch.update(Set.of(5173, 22))); // restarted
    watch.reset();
    eq(Set.of(), watch.update(Set.of(5173, 3000, 8080)));
  }

  // --- mini framework

  private static void test(String name, Runnable body) {
    try {
      body.run();
      passed++;
      System.out.println("ok   " + name);
    } catch (Throwable t) {
      failed.add(name + ": " + t.getMessage());
      System.out.println("FAIL " + name + ": " + t.getMessage());
    }
  }

  private static void eq(Object expected, Object actual) {
    if (!Objects.equals(expected, actual)) throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
  }

  private static void yes(boolean b) {
    if (!b) throw new AssertionError("expected true");
  }

  private static void no(boolean b) {
    if (b) throw new AssertionError("expected false");
  }
}
