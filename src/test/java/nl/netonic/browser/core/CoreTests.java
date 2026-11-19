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
    test("local files: HTML file names, file: URL there and back, label", CoreTests::localFiles);
    test("shorten", CoreTests::shorten);
    test("findDevServerUrl: picks the local URL out of console output", CoreTests::findDevServerUrl);
    test("hostPort: same host:port across equivalent URLs", CoreTests::hostPort);
    test("DevPorts: listening ports from netstat / proc output", CoreTests::devPortsParse);
    test("DevPorts.Watch: reports watched ports that started since the last look", CoreTests::devPortsWatch);
    test("DeviceWidths: page area width per preset, zoom and tab width", CoreTests::deviceWidths);
    test("isLocal: local http(s) pages, also as source", CoreTests::isLocal);
    test("LiveReload: which saves reload a page", CoreTests::liveReload);
    test("ConsoleLog: count, last messages, file names", CoreTests::consoleLog);
    test("Js: string literals and the match-count script", CoreTests::js);

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

  static void localFiles() {
    yes(Urls.isHtmlFile("index.html"));
    yes(Urls.isHtmlFile("Page.HTM"));
    yes(Urls.isHtmlFile("doc.xhtml"));
    no(Urls.isHtmlFile("base.html.twig"));
    no(Urls.isHtmlFile("app.vue"));
    no(Urls.isHtmlFile("html"));
    no(Urls.isHtmlFile(null));
    java.nio.file.Path page = java.nio.file.Path.of("/tmp/my site/café/index.html");
    String url = Urls.fileUrl(page);
    eq("file:///tmp/my%20site/caf%C3%A9/index.html", url);
    eq(page, Urls.localPath(url));
    eq(page, Urls.localPath(url + "?v=2#top"));
    eq(page, Urls.localPath("FILE:///tmp/my%20site/./caf%C3%A9/index.html"));
    eq(null, Urls.localPath("http://localhost/index.html"));
    eq(null, Urls.localPath("view-source:file:///tmp/index.html"));
    eq(null, Urls.localPath("file:relative"));
    eq(null, Urls.localPath(null));
    eq("index.html", Urls.label(url + "#top"));
    eq("view-source:index.html", Urls.label("view-source:" + url));
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

  static void isLocal() {
    yes(Urls.isLocal("http://localhost:3000/"));
    yes(Urls.isLocal("http://backend.test/admin"));
    yes(Urls.isLocal("https://app.localhost"));
    yes(Urls.isLocal("http://127.0.0.1:8765/second.html"));
    yes(Urls.isLocal("HTTP://[::1]:5173/"));
    yes(Urls.isLocal("view-source:http://127.0.0.1:8765/"));
    yes(Urls.isLocal("http://user:pw@printer.local/"));
    no(Urls.isLocal("https://example.com/"));
    no(Urls.isLocal("view-source:https://example.com/"));
    no(Urls.isLocal("https://localhost.example.com/"));
    no(Urls.isLocal("about:blank"));
    no(Urls.isLocal("file:///tmp/x.html"));
    no(Urls.isLocal("http://"));
    no(Urls.isLocal(null));
  }

  static void liveReload() {
    yes(LiveReload.handledByDevServer("app.ts"));
    yes(LiveReload.handledByDevServer("Site.SCSS"));
    yes(LiveReload.handledByDevServer("App.vue"));
    no(LiveReload.handledByDevServer("UserController.php"));
    no(LiveReload.handledByDevServer("base.html.twig"));
    no(LiveReload.handledByDevServer("welcome.blade.php"));
    no(LiveReload.handledByDevServer(".env"));
    no(LiveReload.handledByDevServer(null));
    yes(LiveReload.shouldReload(List.of("app.ts"), false));
    yes(LiveReload.shouldReload(List.of("UserController.php"), true));
    yes(LiveReload.shouldReload(List.of("app.ts", "base.html.twig"), true));
    no(LiveReload.shouldReload(List.of("app.ts", "site.css"), true));
    no(LiveReload.shouldReload(List.of(), false));
    java.nio.file.Path page = java.nio.file.Path.of("/work/site/index.html");
    yes(LiveReload.changesFilePage(page, List.of(java.nio.file.Path.of("/work/site/index.html"))));
    yes(LiveReload.changesFilePage(page, List.of(java.nio.file.Path.of("/work/site/css/app.css"))));
    no(LiveReload.changesFilePage(page, List.of(java.nio.file.Path.of("/work/other/index.html"))));
    no(LiveReload.changesFilePage(page, List.of(java.nio.file.Path.of("/work/site-2/a.css"))));
    no(LiveReload.changesFilePage(null, List.of()));
  }

  static void consoleLog() {
    ConsoleLog log = new ConsoleLog();
    eq(0, log.count());
    log.add("Uncaught ReferenceError: x is not defined", "http://localhost:5173/src/app.js?v=3", 12);
    log.add("Failed to load resource:\n  404", "http://localhost:5173/", 0);
    eq(2, log.count());
    eq(List.of("Uncaught ReferenceError: x is not defined (app.js:12)", "Failed to load resource: 404"), log.recent());
    for (int i = 0; i < 10; i++) log.add("e" + i, "", 0);
    eq(12, log.count());
    eq(List.of("e5", "e6", "e7", "e8", "e9"), log.recent());
    log.clear();
    eq(0, log.count());
    eq(List.of(), log.recent());
    eq("error.html", ConsoleLog.fileName("http://127.0.0.1:8765/error.html#x"));
    eq("", ConsoleLog.fileName("http://localhost:3000"));
    eq("", ConsoleLog.fileName(null));
  }

  static void js() {
    eq("\"a\\\"b\\\\c\\n\\u003c/script>\"", Js.string("a\"b\\c\n</script>"));
    eq("\"\\u2028\\u0001\"", Js.string("\u2028\u0001"));
    eq("\"\"", Js.string(null));
    String script = Js.countMatches("It's", 7);
    yes(script.contains("return '7:' + c;"));
    yes(script.endsWith("(\"It's\")"));
  }

  static void deviceWidths() {
    eq(1000, DeviceWidths.frameWidth(DeviceWidths.FULL, 100, 1000));
    eq(1000, DeviceWidths.frameWidth(DeviceWidths.FULL, 50, 1000));
    eq(390, DeviceWidths.frameWidth(390, 100, 1000));
    eq(195, DeviceWidths.frameWidth(390, 50, 1000));
    eq(312, DeviceWidths.frameWidth(390, 80, 1000));
    eq(800, DeviceWidths.frameWidth(1280, 100, 800)); // a narrow split: never wider than the tab
    eq(0, DeviceWidths.frameWidth(390, 100, 0));
    eq(0, DeviceWidths.frameWidth(DeviceWidths.FULL, 100, -5));
    eq(768, DeviceWidths.parse("768"));
    eq(DeviceWidths.FULL, DeviceWidths.parse(null));
    eq(DeviceWidths.FULL, DeviceWidths.parse("abc"));
    eq(DeviceWidths.FULL, DeviceWidths.parse("-390"));
    eq("Phone (390 px)", DeviceWidths.PRESETS.get(0).text());
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
