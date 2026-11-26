package nl.netonic.browser.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    test("origin: scheme, host and non-default port of http(s) pages", CoreTests::origin);
    test("ColorScheme: saved keys and the emulation parameters", CoreTests::colorScheme);
    test("HttpAuth: Basic challenge realm, Authorization header", CoreTests::httpAuth);
    test("DevPorts: listening ports from netstat / proc output", CoreTests::devPortsParse);
    test("DevPorts.Watch: reports watched ports that started since the last look", CoreTests::devPortsWatch);
    test("DeviceWidths: page area width per preset, zoom and tab width", CoreTests::deviceWidths);
    test("isLocal: local http(s) pages, also as source", CoreTests::isLocal);
    test("LiveReload: which saves reload a page", CoreTests::liveReload);
    test("ConsoleLog: count, last messages, file names", CoreTests::consoleLog);
    test("Js: string literals and the match-count script", CoreTests::js);
    test("SharePreview: tags, fallbacks, warnings, image crop", CoreTests::sharePreview);
    test("ToolbarLayout: defaults, changes, saved form", CoreTests::toolbarLayout);
    test("LoadErrors: which errors show, waiting for a server, sentences", CoreTests::loadErrors);
    test("LoadErrors: HTTP statuses, site, host and port", CoreTests::loadErrorsParts);

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
    yes(Urls.isPdfFile("invoice.pdf"));
    yes(Urls.isPdfFile("/work/docs/Report.PDF"));
    no(Urls.isPdfFile("pdf"));
    no(Urls.isPdfFile("invoice.pdf.php"));
    no(Urls.isPdfFile(null));
    yes(Urls.isPdfViewer("chrome-extension://mhjfbmdgcfjbbpaeojofohoefgiehjai/index.html"));
    no(Urls.isPdfViewer("chrome-extension://abcdefghijklmnopabcdefghijklmnop/index.html"));
    no(Urls.isPdfViewer("http://localhost/mhjfbmdgcfjbbpaeojofohoefgiehjai/"));
    no(Urls.isPdfViewer(null));
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

  static void origin() {
    eq("http://localhost:8000", Urls.origin("http://localhost:8000/admin?x=1#top"));
    eq("https://example.com", Urls.origin("https://Example.COM/a"));
    eq("https://example.com", Urls.origin("https://example.com:443/"));
    eq("http://example.com", Urls.origin("http://example.com:80"));
    eq("https://example.com:8443", Urls.origin("https://example.com:8443/"));
    eq("http://[::1]:5173", Urls.origin("http://[::1]:5173/"));
    eq("http://backend.test", Urls.origin("view-source:http://backend.test/a"));
    eq(null, Urls.origin("about:blank"));
    eq(null, Urls.origin("file:///tmp/x.html"));
    eq(null, Urls.origin("not a url"));
    eq(null, Urls.origin(null));
  }

  static void httpAuth() {
    eq("EB Staging", HttpAuth.basicRealm("Basic realm=\"EB Staging\""));
    eq("EB Staging", HttpAuth.basicRealm("basic Realm = \"EB Staging\", charset=\"UTF-8\""));
    eq("a \"q\" b", HttpAuth.basicRealm("Basic realm=\"a \\\"q\\\" b\""));
    eq("plain", HttpAuth.basicRealm("Basic realm=plain"));
    eq("", HttpAuth.basicRealm("Basic"));
    eq("x", HttpAuth.basicRealm("Negotiate, Basic realm=\"x\""));
    eq(null, HttpAuth.basicRealm("Digest realm=\"x\", nonce=\"Basic\""));
    eq(null, HttpAuth.basicRealm("Bearer"));
    eq(null, HttpAuth.basicRealm(null));
    eq("Basic ZWI6c2VjcmV0", HttpAuth.basicHeader("eb", "secret"));
    eq("Basic w6k6w6g=", HttpAuth.basicHeader("é", "è"));
    yes(HttpAuth.isBasic("Basic ZWI6c2VjcmV0"));
    no(HttpAuth.isBasic("Bearer x"));
    no(HttpAuth.isBasic(null));
  }

  static void colorScheme() {
    eq(ColorScheme.DARK, ColorScheme.parse("dark"));
    eq(ColorScheme.LIGHT, ColorScheme.parse("light"));
    eq(ColorScheme.SYSTEM, ColorScheme.parse(null));
    eq(ColorScheme.SYSTEM, ColorScheme.parse(""));
    eq(ColorScheme.SYSTEM, ColorScheme.parse("sepia"));
    eq("{\"features\":[{\"name\":\"prefers-color-scheme\",\"value\":\"dark\"}]}", ColorScheme.DARK.emulatedMediaParams());
    eq("{\"features\":[{\"name\":\"prefers-color-scheme\",\"value\":\"\"}]}", ColorScheme.SYSTEM.emulatedMediaParams());
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
    // a PDF: no saved file next to it changes it (a PDF changed on disk reloads by PdfFileWatcher)
    no(LiveReload.changesFilePage(java.nio.file.Path.of("/work/site/report.pdf"),
        List.of(java.nio.file.Path.of("/work/site/report.pdf"), java.nio.file.Path.of("/work/site/build.php"))));
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

  static void toolbarLayout() {
    ToolbarLayout d = ToolbarLayout.DEFAULT;
    eq(ToolbarLayout.Place.TOOLBAR, d.place(ToolbarLayout.Item.DEVTOOLS));
    eq(ToolbarLayout.Place.TOOLBAR, d.place(ToolbarLayout.Item.NEW_TAB));
    eq(ToolbarLayout.Place.MENU, d.place(ToolbarLayout.Item.ZOOM));
    eq(ToolbarLayout.Place.MENU, d.place(ToolbarLayout.Item.DEVICE_WIDTH));
    eq(ToolbarLayout.Place.MENU, d.place(ToolbarLayout.Item.SHARE_PREVIEW));
    eq(ToolbarLayout.Place.MENU, d.place(ToolbarLayout.Item.COLOR_SCHEME));
    eq(ToolbarLayout.Place.MENU, d.place(ToolbarLayout.Item.CLEAR_SITE_DATA));
    eq(java.util.Map.of("colorScheme", "toolbar", "clearSiteData", "hidden"), ToolbarLayout.parse(
        java.util.Map.of("colorScheme", "toolbar", "clearSiteData", "hidden")).toStored());
    eq(java.util.Map.of("sharePreview", "toolbar"),
        d.with(ToolbarLayout.Item.SHARE_PREVIEW, ToolbarLayout.Place.TOOLBAR).toStored());
    yes(d.isDefault());
    eq(java.util.Map.of(), d.toStored());

    ToolbarLayout l = d.with(ToolbarLayout.Item.ZOOM, ToolbarLayout.Place.TOOLBAR)
        .with(ToolbarLayout.Item.DEVTOOLS, ToolbarLayout.Place.HIDDEN);
    eq(ToolbarLayout.Place.TOOLBAR, l.place(ToolbarLayout.Item.ZOOM));
    eq(ToolbarLayout.Place.HIDDEN, l.place(ToolbarLayout.Item.DEVTOOLS));
    eq(ToolbarLayout.Place.MENU, l.place(ToolbarLayout.Item.FIND));
    eq(ToolbarLayout.Place.MENU, d.place(ToolbarLayout.Item.ZOOM)); // immutable
    no(l.isDefault());
    // only changes are saved; back to the default place drops the entry
    eq(java.util.Map.of("zoom", "toolbar", "devTools", "hidden"), l.toStored());
    eq(java.util.Map.of("zoom", "toolbar"), l.with(ToolbarLayout.Item.DEVTOOLS, ToolbarLayout.Place.TOOLBAR).toStored());
    eq(l, ToolbarLayout.parse(l.toStored()));

    // unknown items / places (a newer version's) and null are ignored
    eq(d, ToolbarLayout.parse(null));
    eq(java.util.Map.of("find", "hidden"),
        ToolbarLayout.parse(java.util.Map.of("find", "hidden", "bogus", "toolbar", "zoom", "sideways")).toStored());
  }

  static void js() {
    eq("\"a\\\"b\\\\c\\n\\u003c/script>\"", Js.string("a\"b\\c\n</script>"));
    eq("\"\\u2028\\u0001\"", Js.string("\u2028\u0001"));
    eq("\"\"", Js.string(null));
    String script = Js.countMatches("It's", 7);
    yes(script.contains("return '7:' + c;"));
    yes(script.endsWith("(\"It's\")"));
    eq(0xffffff, Js.parseRgb("rgb(255, 255, 255)"));
    eq(0x121212, Js.parseRgb("rgb(18, 18, 18)"));
    eq(0x0a0b0c, Js.parseRgb(" rgba(10,11,12, 1) "));
    eq(-1, Js.parseRgb("rgba(0, 0, 0, 0)"));
    eq(-1, Js.parseRgb("rgb(300, 0, 0)"));
    eq(-1, Js.parseRgb(""));
    eq(-1, Js.parseRgb(null));
    eq(-1, Js.parseRgb("color(srgb 1 1 1)"));
    String cancelled = Js.signInCancelledPage("staging.test", "Team \"A\" </script>");
    yes(cancelled.contains("document.title = 'Sign-in needed'"));
    yes(cancelled.contains("if (location.protocol !== 'chrome-error:') return;"));
    yes(cancelled.endsWith("(\"staging.test\", \"Team \\\"A\\\" \\u003c/script>\")"));
  }

  static void sharePreview() {
    String page = "http://127.0.0.1:8765/share/page.html";
    // the script's output: first value per key wins, empty values and malformed lines are dropped
    Map<String, String> tags = SharePreview.parse("@url\t" + page + "\n@title\tPage\nog:title\tShared title\n"
        + "og:title\tsecond\nog:description\tAbout it\nog:image\t/img/card.png\nnonsense\ndescription\t\n"
        + "og:url\thttps://example.com/page\ntwitter:card\tsummary_large_image");
    eq("Shared title", tags.get("og:title"));
    no(tags.containsKey("description"));
    SharePreview.Card card = SharePreview.card(tags);
    eq("Shared title", card.title());
    eq("About it", card.description());
    eq("http://127.0.0.1:8765/img/card.png", card.image());
    eq("example.com", card.site());
    eq("https://example.com/page", card.url());
    eq(List.of("og:image is a relative URL: crawlers need an absolute one"), card.warnings());

    // nothing but a <title> and a meta description: those show, and the og: tags are reported missing
    SharePreview.Card bare = SharePreview.card(SharePreview.parse("@url\thttps://www.shop.test/x\n@title\tShop\n"
        + "description\tCheap things"));
    eq("Shop", bare.title());
    eq("Cheap things", bare.description());
    eq(null, bare.image());
    eq("shop.test", bare.site());
    eq(List.of("No og:title: apps fall back to <title>", "No og:description: apps fall back to the meta description",
        "No og:image: most apps show a text-only link",
        "No og:url or canonical link: shares of this page under other URLs aren't combined",
        "No twitter:card: X shows a small card (summary_large_image for a big image)"), bare.warnings());

    // twitter: fallbacks, og:site_name, canonical, too long, local image on a public page
    String longTitle = "x".repeat(SharePreview.TITLE_MAX + 1);
    SharePreview.Card tw = SharePreview.card(SharePreview.parse("@url\thttps://example.com/a b\n"
        + "twitter:title\t" + longTitle + "\ntwitter:description\td\ntwitter:image\thttp://localhost:3000/i.png\n"
        + "og:site_name\tExample\n@canonical\t/a\ntwitter:card\tsummary"));
    eq(longTitle, tw.title());
    eq("Example", tw.site());
    eq("https://example.com/a", tw.url());
    eq("http://localhost:3000/i.png", tw.image());
    eq(List.of("No og:title: apps fall back to twitter:title", "No og:description: apps fall back to twitter:description",
        "og:image points at a local server that other sites can't reach",
        "Title is 61 characters: most apps cut it off after about 60"), tw.warnings());

    eq(List.of("No title: add og:title (or a <title>)", "No description: add og:description",
        "No og:image: most apps show a text-only link",
        "No og:url or canonical link: shares of this page under other URLs aren't combined",
        "No twitter:card: X shows a small card (summary_large_image for a big image)"),
        SharePreview.card(SharePreview.parse(null)).warnings());

    eq(null, SharePreview.imageWarning(1200, 630));
    eq(null, SharePreview.imageWarning(0, 0));
    yes(SharePreview.imageWarning(1, 1).startsWith("Image is 1 × 1 px"));
    // cover crop to 1.91 : 1: a square loses top and bottom, a wide banner its sides
    eq(List.of(0, 237, 1000, 525), box(SharePreview.coverCrop(1000, 1000)));
    eq(List.of(0, 0, 1200, 630), box(SharePreview.coverCrop(1200, 630)));
    eq(List.of(400, 0, 1200, 630), box(SharePreview.coverCrop(2000, 630)));
  }

  private static List<Integer> box(int[] r) {
    return List.of(r[0], r[1], r[2], r[3]);
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

  static void loadErrors() {
    yes(LoadErrors.shown("ERR_CONNECTION_REFUSED"));
    yes(LoadErrors.shown("ERR_EMPTY_RESPONSE"));
    no(LoadErrors.shown("ERR_ABORTED"));
    no(LoadErrors.shown("ERR_NONE"));
    no(LoadErrors.shown("ERR_INVALID_AUTH_CREDENTIALS"));
    no(LoadErrors.shown(null));
    yes(LoadErrors.waitsForServer("ERR_CONNECTION_REFUSED", "http://localhost:5173/"));
    yes(LoadErrors.waitsForServer("ERR_CONNECTION_REFUSED", "http://backend.test/admin"));
    no(LoadErrors.waitsForServer("ERR_CONNECTION_REFUSED", "https://example.com/"));
    no(LoadErrors.waitsForServer("ERR_EMPTY_RESPONSE", "http://localhost:5173/"));
    eq("Nothing is answering on localhost:5173. Is the server running?",
        LoadErrors.describe("ERR_CONNECTION_REFUSED", "http://localhost:5173/x"));
    eq("Nothing is answering on example.com.", LoadErrors.describe("ERR_CONNECTION_REFUSED", "https://example.com/"));
    eq("Can't find shop.test: is it in /etc/hosts or your local DNS?",
        LoadErrors.describe("ERR_NAME_NOT_RESOLVED", "http://shop.test/"));
    eq("Can't find exampel.com: check the address.", LoadErrors.describe("ERR_NAME_NOT_RESOLVED", "https://exampel.com/"));
    eq("localhost:8000 can't make a secure connection. Does it only speak http://?",
        LoadErrors.describe("ERR_SSL_PROTOCOL_ERROR", "https://localhost:8000/"));
    eq("127.0.0.1:8765 closed the connection without an answer. Did the server crash? Check its log.",
        LoadErrors.describe("ERR_EMPTY_RESPONSE", "http://127.0.0.1:8765/drop"));
    eq("example.com's certificate isn't trusted.", LoadErrors.describe("ERR_CERT_AUTHORITY_INVALID", "https://example.com"));
    eq("The page couldn't load.", LoadErrors.describe("ERR_SOMETHING_NEW", "https://example.com"));
    eq("Can't open localhost:3000", LoadErrors.title("http://localhost:3000/a?b"));
  }

  static void loadErrorsParts() {
    eq("404 Not Found", LoadErrors.httpStatus(404));
    eq("500 Internal Server Error", LoadErrors.httpStatus(500));
    eq("599", LoadErrors.httpStatus(599));
    yes(LoadErrors.isHttpError(404));
    yes(LoadErrors.isHttpError(503));
    no(LoadErrors.isHttpError(200));
    no(LoadErrors.isHttpError(304));
    no(LoadErrors.isHttpError(0));
    eq("localhost:5173", LoadErrors.site("http://LocalHost:5173/app"));
    eq("example.com", LoadErrors.site("https://example.com/"));
    eq("the page", LoadErrors.site(""));
    eq("::1", LoadErrors.hostOf("http://[::1]:3000/"));
    eq("localhost", LoadErrors.hostOf("http://localhost:3000/"));
    eq(null, LoadErrors.hostOf("about:blank"));
    eq(3000, LoadErrors.portOf("http://localhost:3000/"));
    eq(80, LoadErrors.portOf("http://backend.test/"));
    eq(443, LoadErrors.portOf("https://example.com"));
    eq(-1, LoadErrors.portOf("about:blank"));
  }

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
