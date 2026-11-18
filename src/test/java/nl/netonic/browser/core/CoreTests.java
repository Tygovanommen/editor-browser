package nl.netonic.browser.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
    test("shorten", CoreTests::shorten);
    test("findDevServerUrl: picks the local URL out of console output", CoreTests::findDevServerUrl);
    test("hostPort: same host:port across equivalent URLs", CoreTests::hostPort);

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
