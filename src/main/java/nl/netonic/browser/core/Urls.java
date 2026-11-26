package nl.netonic.browser.core;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What the user typed in the address bar → a URL, and a URL → a short tab label. Pure JDK, see CoreTests. */
public final class Urls {
  public static final String BLANK = "about:blank";
  public static final String VIEW_SOURCE = "view-source:";

  private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");
  private static final Pattern NO_SLASH_SCHEME = Pattern.compile("^(about|data|file|javascript|mailto|view-source):.*",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");
  private static final Pattern ANSI_CODE = Pattern.compile("\u001B\\[[0-9;]*m");
  private static final Pattern URL_TOKEN = Pattern.compile("https?://[^\\s\"'<>]+");

  private Urls() {}

  /**
   * {@code localhost:3000} → {@code http://localhost:3000}, {@code example.com} → {@code https://example.com}.
   * Local hosts (localhost, IPs, {@code .test}, {@code .local}, {@code .localhost}) get http, like Herd and Valet
   * serve them; anything else https. Returns null for blank input.
   */
  public static String normalize(String input) {
    if (input == null) return null;
    String s = input.strip();
    if (s.isEmpty()) return null;
    if (SCHEME.matcher(s).matches() || NO_SLASH_SCHEME.matcher(s).matches()) return s;
    if (s.startsWith("//")) s = s.substring(2);
    return (isLocalHost(host(s)) ? "http://" : "https://") + s;
  }

  /** True when the text could be an address: a scheme, localhost, or a host with a dot and no spaces. */
  public static boolean looksLikeUrl(String text) {
    if (text == null) return false;
    String s = text.strip();
    if (s.isEmpty() || s.length() > 2048 || s.chars().anyMatch(Character::isWhitespace)) return false;
    if (SCHEME.matcher(s).matches()) return s.toLowerCase(Locale.ROOT).matches("^(https?|file)://.+");
    String host = host(s);
    if (host.isEmpty()) return false;
    if (isLocalHost(host)) return true;
    int dot = host.lastIndexOf('.');
    // a dot that isn't the first or last character, and a letters-only TLD ("app.vue" is not an address)
    return dot > 0 && dot < host.length() - 1 && host.substring(dot + 1).matches("[a-zA-Z]{2,24}")
        && !isLikelyFileName(host);
  }

  /** Short label for a tab before the page has a title: {@code localhost:3000}, {@code example.com}. */
  public static String label(String url) {
    if (url == null || url.isBlank() || url.equalsIgnoreCase(BLANK)) return "New Tab";
    if (isViewSource(url)) return VIEW_SOURCE + label(url.substring(VIEW_SOURCE.length()));
    Path local = localPath(url);
    if (local != null && local.getFileName() != null) return local.getFileName().toString();
    try {
      URI uri = URI.create(url);
      String host = uri.getHost();
      if (host != null) {
        if (host.startsWith("www.")) host = host.substring(4);
        return uri.getPort() > 0 ? host + ":" + uri.getPort() : host;
      }
    } catch (IllegalArgumentException ignored) {
      // not a parseable URI, fall through
    }
    String rest = url.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*:(//)?", "");
    int cut = indexOfAny(rest, "/?#");
    String label = cut >= 0 ? rest.substring(0, cut) : rest;
    return label.isEmpty() ? url : label;
  }

  /**
   * The address that shows {@code url}'s HTML source ({@code view-source:https://example.com/}), or null when there
   * is no page to show the source of (blank, {@code about:blank}). A source URL stays as it is.
   */
  public static String viewSource(String url) {
    if (url == null || url.isBlank() || url.strip().equalsIgnoreCase(BLANK)) return null;
    String s = url.strip();
    return isViewSource(s) ? s : VIEW_SOURCE + s;
  }

  /** True for a {@code view-source:} address. */
  public static boolean isViewSource(String url) {
    return url != null && url.regionMatches(true, 0, VIEW_SOURCE, 0, VIEW_SOURCE.length());
  }

  /**
   * True for an http(s) page on a local host (localhost, an IP, {@code .test}, {@code .local}, {@code .localhost}),
   * also shown as source: the pages a saved project file can change.
   */
  public static boolean isLocal(String url) {
    if (url == null) return false;
    String s = url.strip();
    if (isViewSource(s)) s = s.substring(VIEW_SOURCE.length());
    if (!s.regionMatches(true, 0, "http://", 0, 7) && !s.regionMatches(true, 0, "https://", 0, 8)) return false;
    String host = host(s.substring(s.indexOf("://") + 3));
    return !host.isEmpty() && isLocalHost(host);
  }

  /** True for a file Chromium shows as a page: {@code index.html}, {@code page.htm}, {@code doc.xhtml}. */
  public static boolean isHtmlFile(String fileName) {
    String ext = extension(fileName);
    return ext.equals("html") || ext.equals("htm") || ext.equals("xhtml");
  }

  /** True for a PDF file ({@code report.pdf}), which Chromium shows in its PDF viewer. */
  public static boolean isPdfFile(String fileName) {
    return extension(fileName).equals("pdf");
  }

  /**
   * True for the frame of Chromium's built-in PDF viewer (an extension, the same id as in Chrome): a page showing
   * a PDF has it inside.
   */
  public static boolean isPdfViewer(String frameUrl) {
    return frameUrl != null && frameUrl.startsWith("chrome-extension://" + PDF_VIEWER_EXTENSION + "/");
  }

  private static final String PDF_VIEWER_EXTENSION = "mhjfbmdgcfjbbpaeojofohoefgiehjai";

  /** The {@code file:} URL of a local file, non-ASCII characters and spaces percent-encoded as Chromium does. */
  public static String fileUrl(Path file) {
    return file.toAbsolutePath().toUri().toASCIIString();
  }

  /** The local file a {@code file:} URL points at (query and fragment ignored), or null for any other URL. */
  public static Path localPath(String url) {
    if (url == null || !url.regionMatches(true, 0, "file:", 0, 5)) return null;
    int cut = indexOfAny(url, "?#");
    try {
      return Path.of(URI.create(cut >= 0 ? url.substring(0, cut) : url)).normalize();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String extension(String fileName) {
    if (fileName == null) return "";
    int dot = fileName.lastIndexOf('.');
    return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
  }

  /**
   * The first local-looking URL in a chunk of console output ({@code http://localhost:5173/},
   * {@code http://127.0.0.1:8080}), ANSI color codes ignored, or null when there isn't one. For detecting a dev
   * server's address in a run configuration's output.
   */
  public static String findDevServerUrl(String text) {
    if (text == null) return null;
    Matcher m = URL_TOKEN.matcher(ANSI_CODE.matcher(text).replaceAll(""));
    while (m.find()) {
      String candidate = trimTrailingPunctuation(m.group());
      if (isLocalHost(host(candidate.replaceFirst("^https?://", "")))) return candidate;
    }
    return null;
  }

  /**
   * The page's origin as Chromium writes it ({@code http://localhost:8000}, {@code https://example.com}: lower-case
   * host, default port left out), or null for anything but an http(s) page (a source view counts as its page).
   */
  public static String origin(String url) {
    if (url == null) return null;
    String s = url.strip();
    if (isViewSource(s)) s = s.substring(VIEW_SOURCE.length());
    try {
      URI uri = URI.create(s);
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      String host = uri.getHost();
      if (host == null || !(scheme.equals("http") || scheme.equals("https"))) return null;
      int port = uri.getPort();
      boolean defaultPort = port < 0 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
      return scheme + "://" + host.toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * {@code host:port} for matching the same address across runs, or null when {@code url} has no host. Loopback
   * names ({@code 127.0.0.1}, {@code [::1]}) count as {@code localhost}: the same dev server either way.
   */
  public static String hostPort(String url) {
    try {
      URI uri = URI.create(url);
      String host = uri.getHost();
      if (host == null) return null;
      int port = uri.getPort();
      if (port < 0) port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
      host = host.toLowerCase(Locale.ROOT);
      if (host.equals("127.0.0.1") || host.equals("[::1]")) host = "localhost";
      return host + ":" + port;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private static String trimTrailingPunctuation(String s) {
    int end = s.length();
    while (end > 0 && ".,;:!?)]}".indexOf(s.charAt(end - 1)) >= 0) end--;
    return s.substring(0, end);
  }

  /** {@code text} cut to {@code max} characters with an ellipsis. */
  public static String shorten(String text, int max) {
    if (text == null) return "";
    String s = text.strip();
    return s.length() <= max ? s : s.substring(0, Math.max(1, max - 1)).stripTrailing() + "…";
  }

  /** The host part of something typed without a scheme: {@code localhost:3000/x} → {@code localhost}. */
  static String host(String noScheme) {
    String s = noScheme;
    int at = s.indexOf('@');
    int cut = indexOfAny(s, "/?#");
    if (at >= 0 && (cut < 0 || at < cut)) s = s.substring(at + 1);
    cut = indexOfAny(s, "/?#");
    if (cut >= 0) s = s.substring(0, cut);
    int colon = s.lastIndexOf(':');
    if (colon >= 0 && !s.startsWith("[")) s = s.substring(0, colon);
    return s.toLowerCase(Locale.ROOT);
  }

  static boolean isLocalHost(String host) {
    return host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".test")
        || host.endsWith(".local") || host.startsWith("[") || IPV4.matcher(host).matches();
  }

  private static boolean isLikelyFileName(String host) {
    String ext = host.substring(host.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    return switch (ext) {
      case "php", "js", "ts", "vue", "json", "md", "html", "css", "scss", "xml", "yml", "yaml", "txt", "java",
           "kt", "lock", "env", "sh", "sql", "log", "png", "jpg", "svg", "zip" -> true;
      default -> false;
    };
  }

  private static int indexOfAny(String s, String chars) {
    for (int i = 0; i < s.length(); i++) if (chars.indexOf(s.charAt(i)) >= 0) return i;
    return -1;
  }
}
